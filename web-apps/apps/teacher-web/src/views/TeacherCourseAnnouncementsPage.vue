<script setup lang="ts">
/**
 * 本人课程公告页：课程 owner 详情决定可写状态，公告列表和审计各自服从服务端授权。
 * 输入为固定路由及有界分页/选中项查询；输出为纯文本即时发布、乐观锁改版和确认撤回。
 * 未知写入结果只允许有限次原命令重试；离页、身份变化和卸载取消请求并清理私有内容。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { AnnouncementPage, AnnouncementRevisionPage, CourseAnnouncement, TeacherCourse } from "@edu/api-client";
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from "vue-router";
import { authStore } from "../auth";
import { teacherAnnouncementApi as api } from "../announcement-api";
import { teacherCourseApi } from "../course-api";

const route = useRoute(); const router = useRouter();
const courseId = computed(() => String(route.params.courseId));
/** URL 分页不接受数组、非整数或超过安全偏移范围的值；非法查询回到第一页，不扫描其他页。 */
function pageNumber(value: unknown): number {
  return typeof value === "string" && /^[1-9]\d{0,5}$/.test(value) ? Number(value) : 1;
}
const page = computed(() => pageNumber(route.query.page));
const auditPage = computed(() => pageNumber(route.query.auditPage));
const selectedId = computed(() => typeof route.query.announcementId === "string" && /^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(route.query.announcementId)
  ? route.query.announcementId : "");
const panel = computed(() => ["create", "edit", "audit"].includes(String(route.query.panel)) ? String(route.query.panel) : "list");
const course = ref<TeacherCourse | null>(null); const list = ref<AnnouncementPage | null>(null);
const audit = ref<AnnouncementRevisionPage | null>(null);
const loading = ref(false); const auditLoading = ref(false); const busy = ref(false);
const courseError = ref(""); const listError = ref(""); const auditError = ref(""); const writeError = ref("");
const notice = ref(""); const fields = ref<Record<string, string>>({}); const denied = ref(false);
const conflict = ref(false); const withdrawConfirm = ref(false);
const draft = reactive({ title: "", body: "", revision: 0 });
const detailPane = ref<HTMLElement | null>(null);
let baseline = ""; const dirty = ref(false); let initialized = "";
let loadedLocation = "";
let readController: AbortController | undefined; let auditController: AbortController | undefined;
let writeController: AbortController | undefined; let cooldownTimer: ReturnType<typeof setTimeout> | undefined;
const cooling = ref(false);
/** 内存中的逻辑命令快照：重试复用原 ID、版本和输入，不能被刷新后的列表事实或草稿覆盖。 */
interface WriteCommand {
  signature: string; id: string; attempts: number;
  operation: "create" | "update" | "withdraw";
  courseId: string; announcementId: string; revision: number; title: string; body: string;
  retryable: boolean;
}
const command = ref<WriteCommand | null>(null);
// dirty 表示编辑变更，busy 表示请求进行中，unresolved 表示曾发送但结果未决，三者独立保护离页。
const unresolved = ref(false);
const selected = computed(() => list.value?.items.find(item => item.id === selectedId.value));
const canTeach = computed(() => authStore.state.currentUser?.roles.includes("TEACHER")
  && authStore.state.currentUser.permissions.includes("teaching:access"));
const writable = computed(() => canTeach.value && !denied.value && !courseError.value
  && course.value?.publicationStatus === "PUBLISHED");
const editable = computed(() => panel.value === "create" || (panel.value === "edit" && selected.value?.status === "ACTIVE"));
const canSend = computed(() => writable.value && !loading.value && !busy.value && !cooling.value && !conflict.value && !unresolved.value);
const canRetry = computed(() => unresolved.value && command.value?.retryable && command.value.attempts < 3
  && writable.value && !loading.value && !busy.value && !cooling.value && !conflict.value);
const pages = computed(() => Math.max(1, Math.ceil((list.value?.total ?? 0) / 20)));
const auditPages = computed(() => Math.max(1, Math.ceil((audit.value?.total ?? 0) / 20)));
const returnTarget = computed(() => route.query.from === "edit" || route.query.from === "curriculum"
  ? { name: `teacher-course-${route.query.from}`, params: { courseId: courseId.value } }
  : { name: "teacher-courses", query: { page: String(pageNumber(route.query.coursePage)) } });

/** 只记录内存草稿；未知结果期间冻结命令快照，明确结束未决命令后输入变化才开始新命令。 */
watch(() => JSON.stringify(draft), value => {
  dirty.value = editable.value && value !== baseline;
  if (!unresolved.value) { command.value = null; fields.value = {}; writeError.value = ""; }
}, { flush: "sync" });

function resetDraft(item?: CourseAnnouncement) {
  Object.assign(draft, { title: item?.title ?? "", body: item?.body ?? "", revision: item?.revision ?? 0 });
  baseline = JSON.stringify(draft); dirty.value = false;
  if (!unresolved.value) command.value = null;
  fields.value = {}; writeError.value = ""; conflict.value = false; withdrawConfirm.value = false;
}
/** 授权失败清除所有受保护投影，避免并发成功响应重新显示旧身份的正文；暂停只关闭写入。 */
function clearProtected() {
  readController?.abort(); auditController?.abort(); writeController?.abort();
  course.value = null; list.value = null; audit.value = null;
  // 授权或身份变化立即清除私有快照并禁止重试；仅留下未决标记、ID 和次数供用户确认放弃。
  if (unresolved.value && command.value) {
    command.value.title = ""; command.value.body = ""; command.value.signature = ""; command.value.retryable = false;
  }
  resetDraft(); initialized = ""; denied.value = true;
  loading.value = false; auditLoading.value = false; busy.value = false;
}
function isDenied(error: unknown) {
  return error instanceof GatewayRequestError && [401, 403, 404].includes(error.status)
    || error instanceof Error && error.message === "AUTHENTICATION_REQUIRED";
}
/** 错误文案只展示稳定状态与可行动信息；限流冷却结束只恢复手动按钮，不自动重发命令。 */
function errorText(error: unknown): string {
  if (error instanceof GatewayRequestError) {
    if (error.status === 429) {
      const seconds = Math.max(1, error.retryAfterSeconds ?? 30);
      cooling.value = true; clearTimeout(cooldownTimer);
      cooldownTimer = setTimeout(() => { cooling.value = false; }, Math.min(seconds * 1000, 2147483647));
      return `请求过于频繁，请至少等待 ${seconds} 秒后重试。`;
    }
    return ({ 401: "登录状态已失效，请重新登录。", 403: "当前账号没有公告访问权限。",
      404: "课程或公告不存在，或不属于当前教师。", 409: "课程状态或公告版本已变化，请核对最新内容。",
      422: "请求校验失败，请检查字段及请求错误。", 503: "服务暂时不可用，请稍后重试。" } as Record<number, string>)[error.status]
      ?? "公告请求失败，请稍后重试。";
  }
  if (error instanceof Error && error.message === "AUTHENTICATION_REQUIRED") return "登录状态已失效，请重新登录。";
  return error instanceof TypeError ? "网络不可用，请恢复网络后重试。" : "请求未完成，请稍后重试。";
}

/** 并行加载 owner 状态和当前一页；区域故障保留同身份已成功内容，绝不为定位公告遍历分页。 */
async function load() {
  if (!canTeach.value) { clearProtected(); listError.value = "当前账号没有教学能力；管理员不能代发公告。"; return; }
  readController?.abort(); auditController?.abort(); auditLoading.value = false;
  const controller = new AbortController(); readController = controller;
  const id = courseId.value; const requestedPage = page.value;
  const location = `${id}:${requestedPage}`;
  if (loadedLocation !== location) { course.value = null; list.value = null; audit.value = null; loadedLocation = location; }
  loading.value = true; courseError.value = ""; listError.value = "";
  const current = () => !controller.signal.aborted && readController === controller;
  await Promise.allSettled([
    teacherCourseApi.detail(id, controller.signal).then(value => { if (current()) course.value = value; }).catch(error => {
      if (!current()) return;
      course.value = null; courseError.value = errorText(error);
      if (error instanceof GatewayRequestError && error.code === "COURSE.SUSPENDED") courseError.value = "课程已暂停，不能写入公告；历史读取以公告服务授权为准。";
      if (isDenied(error)) { clearProtected(); listError.value = courseError.value; }
    }),
    api.list(id, requestedPage, controller.signal).then(value => {
      if (!current()) return;
      list.value = value; denied.value = false;
    }).catch(error => {
      if (!current()) return;
      listError.value = errorText(error); if (isDenied(error)) clearProtected();
    })
  ]);
  if (!current()) return;
  loading.value = false;
  const key = `${id}:${panel.value}:${selectedId.value}`;
  const changedPanel = initialized !== key;
  if (changedPanel) {
    initialized = key;
    resetDraft(panel.value === "edit" ? selected.value : undefined);
  }
  if (panel.value === "audit" && selected.value) await loadAudit();
  // 手机上操作区在列表之后；新面板加载完再移焦点，刷新同面板不夺取正在编辑的焦点。
  if (changedPanel && current()) { await nextTick(); detailPane.value?.focus(); }
}

/** 审计只读取当前列表内明确选中的公告及一页历史；失败不覆盖列表或编辑草稿。 */
async function loadAudit() {
  auditController?.abort(); audit.value = null; auditError.value = "";
  if (!selected.value || denied.value) return;
  const controller = new AbortController(); auditController = controller; auditLoading.value = true;
  try {
    const result = await api.revisions(courseId.value, selected.value.id, auditPage.value, controller.signal);
    if (!controller.signal.aborted) audit.value = result;
  } catch (error) {
    if (!controller.signal.aborted) { auditError.value = errorText(error); if (isDenied(error)) clearProtected(); }
  } finally { if (!controller.signal.aborted) auditLoading.value = false; }
}

/** 只合并白名单查询；分页、面板及返回来源留在 URL，浏览器返回和刷新可恢复定位。 */
async function navigate(changes: Record<string, string | undefined>) {
  await router.push({ name: "teacher-course-announcements", params: { courseId: courseId.value }, query: {
    page: String(page.value), coursePage: String(pageNumber(route.query.coursePage)),
    from: ["edit", "curriculum"].includes(String(route.query.from)) ? String(route.query.from) : undefined,
    announcementId: selectedId.value || undefined, panel: panel.value === "list" ? undefined : panel.value,
    auditPage: String(auditPage.value), ...changes
  } });
}
function choose(item: CourseAnnouncement, mode: string) { void navigate({ panel: mode, announcementId: item.id, auditPage: "1" }); }
/** 冲突后保留正文，只有用户明确采用当前列表的 revision 才允许再次提交；撤回不能自动采用新版本。 */
function adoptRevision() {
  if (!selected.value || selected.value.status !== "ACTIVE") return;
  draft.revision = selected.value.revision; conflict.value = false;
  notice.value = "已采用最新 revision。请核对正文后再次提交。";
}
function validate(): boolean {
  fields.value = {};
  if (!draft.title.trim() || draft.title.length > 120 || /[\u0000-\u001f\u007f-\u009f]/.test(draft.title)) fields.value.title = "标题须为 1–120 个字符，不含控制字符。";
  if (!draft.body.trim() || draft.body.length > 20000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(draft.body)) fields.value.body = "正文须为 1–20000 个字符，仅允许换行和制表符。";
  return Object.keys(fields.value).length === 0;
}

/** 从当前已授权的编辑/撤回输入创建命令；未决状态只能从独立入口重试原快照。 */
async function send(kind: "save" | "withdraw") {
  if (!canSend.value || kind === "save" && (!editable.value || !validate())) return;
  const item = selected.value;
  if (kind === "withdraw" && (!withdrawConfirm.value || item?.status !== "ACTIVE")) return;
  const operation = kind === "withdraw" ? "withdraw" : panel.value === "create" ? "create" : "update";
  const revision = kind === "withdraw" ? item!.revision : draft.revision;
  const signature = JSON.stringify([courseId.value, operation, item?.id, revision, draft.title, draft.body]);
  if (!command.value || command.value.signature !== signature) command.value = {
    signature, id: crypto.randomUUID(), attempts: 0, operation, courseId: courseId.value, announcementId: item?.id ?? "",
    revision, title: draft.title, body: draft.body, retryable: true
  };
  await executeCommand(command.value);
}

/** 用户手动重试未决写入，最多三次且复用原 ID/revision/正文；权限、冷却和冲突仍禁止发送。 */
async function retryCommand() {
  if (canRetry.value && command.value) await executeCommand(command.value);
}

/** 执行固定快照并记录有限次数；后续明确失败不能证明先前未知请求未写入，必须保留未决状态。 */
async function executeCommand(snapshot: WriteCommand) {
  if (snapshot.attempts >= 3) { writeError.value = "原命令已尝试三次。请刷新列表核对是否已成功；不要重复发布同一公告。"; return; }
  snapshot.attempts++;
  const { operation, courseId: id, announcementId, revision, title, body } = snapshot;
  const requestId = snapshot.id;
  writeController?.abort(); const controller = new AbortController(); writeController = controller;
  busy.value = true; writeError.value = ""; fields.value = {}; notice.value = "";
  try {
    if (operation === "withdraw") await api.withdraw(id, announcementId, { clientRequestId: requestId, expectedRevision: revision }, controller.signal);
    else if (operation === "create") await api.create(id, { clientRequestId: requestId, title, body }, controller.signal);
    else await api.update(id, announcementId, { clientRequestId: requestId, expectedRevision: revision, title, body }, controller.signal);
    if (controller.signal.aborted) return;
    dirty.value = false; unresolved.value = false; command.value = null; withdrawConfirm.value = false;
    notice.value = operation === "withdraw" ? "公告已撤回，学生旧链接将不可读。" : "公告已立即发布，学生需要确认当前版本。";
    busy.value = false;
    if (panel.value !== "list") await navigate({ panel: undefined, announcementId: undefined, page: "1" });
    else await load();
  } catch (error) {
    if (controller.signal.aborted) return;
    writeError.value = errorText(error);
    if (error instanceof GatewayRequestError) fields.value = error.fieldErrors;
    if (isDenied(error)) { const message = writeError.value; clearProtected(); writeError.value = message; }
    else if (error instanceof GatewayRequestError && error.status === 409) {
      conflict.value = true; withdrawConfirm.value = false;
      if (!unresolved.value) command.value = null;
      await load();
      writeError.value = "课程状态或公告版本已变化。输入已保留；请核对最新内容，并明确采用新 revision 后再提交。";
    } else if (!(error instanceof GatewayRequestError) || error.status >= 500) {
      unresolved.value = true;
      writeError.value += " 写入结果尚不确定：先刷新列表核对，重试会使用同一请求 ID。";
    }
  } finally { if (!controller.signal.aborted) busy.value = false; }
}

/** 用户已核对结果或确认放弃时结束未决命令；不把人工结束当作写入成功，也不清除编辑草稿。 */
function endUnresolved(abandon: boolean) {
  if (busy.value || !unresolved.value) return;
  if (abandon && !window.confirm("写入可能已在服务端生效。确定放弃本次重试吗？放弃不会撤销已完成的写入。")) return;
  unresolved.value = false; command.value = null; writeError.value = ""; fields.value = {};
  withdrawConfirm.value = false;
  notice.value = abandon ? "已放弃本次重试，请按服务端实际结果处理。" : "已结束本次重试，请按已核对的处理结果继续操作。";
}

/** 刷新只请求浏览器确认，不清除命令；取消路由离页保留 ID/次数，确认放弃才取消请求并释放提示。 */
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value || unresolved.value) { event.preventDefault(); event.returnValue = ""; } }
function allowLeave() {
  if (!(dirty.value || busy.value || unresolved.value)) return true;
  if (!window.confirm("有未保存的公告或尚未确认的写入结果。确定离开并放弃输入及本次重试吗？已生效的写入不会撤销。")) return false;
  writeController?.abort(); busy.value = false; dirty.value = false; unresolved.value = false; command.value = null;
  return true;
}
onBeforeRouteLeave(allowLeave);
onBeforeRouteUpdate((to, from) => to.fullPath === from.fullPath || allowLeave());
window.addEventListener("beforeunload", beforeUnload);
watch(() => route.fullPath, () => { writeController?.abort(); busy.value = false; withdrawConfirm.value = false; void load(); }, { immediate: true });
watch([() => authStore.state.currentUser?.id, () => authStore.state.currentUser?.sessionVersion, canTeach], () => {
  clearProtected(); void load();
});
onBeforeUnmount(() => { clearProtected(); command.value = null; clearTimeout(cooldownTimer); window.removeEventListener("beforeunload", beforeUnload); });
</script>

<template>
  <section class="announcements-page">
    <RouterLink class="back-link" :to="returnTarget">← 返回{{ route.query.from === 'edit' ? '基本信息' : route.query.from === 'curriculum' ? '章节与课时' : '课程管理' }}</RouterLink>
    <header class="teacher-page-heading">
      <div><span class="section-kicker">COURSE ANNOUNCEMENTS</span><h2>课程公告</h2><p>{{ course?.title ?? '本人课程公告与历史版本' }}</p></div>
      <div class="announcement-actions">
        <button type="button" :disabled="loading || busy || cooling" @click="load">刷新</button>
        <button class="button button--primary" type="button" :disabled="!writable || loading || busy || cooling" @click="navigate({ panel: 'create', announcementId: undefined })">发布公告</button>
      </div>
    </header>
    <p v-if="courseError" class="feedback feedback--warning" role="alert">{{ courseError }}</p>
    <p v-else-if="course && !writable" class="feedback feedback--warning" role="status">仅已发布且未暂停的本人课程允许写入。当前课程{{ course.publicationStatus === 'DRAFT' ? '为草稿' : '已下架' }}；历史仍由服务端授权。</p>
    <p v-if="notice" class="feedback feedback--success" role="status">{{ notice }}</p>
    <div v-if="unresolved" class="feedback feedback--warning" role="status">
      <p>写入结果尚不确定。请先刷新列表核对；仅重试原命令，最多发送三次。离开或刷新页面仍需确认。</p>
      <p v-if="command && command.attempts >= 3">原命令已尝试三次。请核对处理结果或确认放弃本次重试。</p>
      <div class="announcement-actions">
        <button type="button" :disabled="!canRetry" @click="retryCommand">重试原命令</button>
        <button type="button" :disabled="busy || loading" @click="endUnresolved(false)">已核对处理结果，结束本次重试</button>
        <button type="button" :disabled="busy" @click="endUnresolved(true)">放弃本次重试</button>
      </div>
    </div>
    <p v-if="loading" role="status">正在读取课程状态和公告…</p>
    <p v-if="listError" class="feedback feedback--error" role="alert">{{ listError }} <button :disabled="loading || cooling" type="button" @click="load">重试列表</button></p>
    <div class="announcement-layout">
      <section aria-label="课程公告列表">
        <p v-if="list && !list.items.length">这一页暂无公告。{{ list.total ? '请返回前面的分页。' : '发布后，学生将在消息中心和课程详情看到公告。' }}</p>
        <article v-for="item in list?.items ?? []" :key="item.id" class="announcement-card" :class="{ 'is-selected': selectedId === item.id }">
          <p class="announcement-meta">{{ item.status === 'ACTIVE' ? '已发布' : '已撤回' }} · revision {{ item.revision }}</p>
          <h3>{{ item.title }}</h3><p class="announcement-body" tabindex="0" aria-label="公告正文">{{ item.body }}</p>
          <p class="announcement-meta">更新于 {{ new Date(item.updatedAt).toLocaleString('zh-CN') }}</p>
          <div class="announcement-actions">
            <button type="button" @click="choose(item, 'audit')">审计版本</button>
            <button v-if="item.status === 'ACTIVE'" type="button" :disabled="!writable || loading || busy || cooling" @click="choose(item, 'edit')">改版</button>
            <button v-if="item.status === 'ACTIVE'" type="button" :disabled="!writable || loading || busy || cooling" @click="choose(item, 'list'); withdrawConfirm = false">选择撤回</button>
          </div>
        </article>
        <nav v-if="list" class="pagination" aria-label="公告分页">
          <button type="button" :disabled="page <= 1 || loading || cooling" @click="navigate({ page: String(page - 1) })">上一页</button>
          <span>第 {{ page }} / {{ pages }} 页 · 共 {{ list.total }} 条</span>
          <button type="button" :disabled="page >= pages || loading || cooling" @click="navigate({ page: String(page + 1) })">下一页</button>
        </nav>
      </section>
      <section v-if="!denied && (panel !== 'list' || selectedId)" ref="detailPane" tabindex="-1" class="announcement-detail" aria-label="公告操作">
        <p v-if="selectedId && !selected && !loading" role="status">选中公告不在当前已载分页。请切换到公告所在页；本页不会遍历其他分页或调用单公告接口。</p>
        <form v-if="panel === 'create' || panel === 'edit' && selected" @submit.prevent="send('save')">
          <h3>{{ panel === 'create' ? '立即发布纯文本公告' : `改版 · 基于 revision ${draft.revision}` }}</h3>
          <p>不支持附件、富文本或定时发布。改版后学生需重新确认。</p>
          <label class="editor-field" for="announcement-title">标题（最多 120 字符）
            <input id="announcement-title" v-model="draft.title" maxlength="120" :disabled="loading || busy || unresolved || !writable || !editable" :aria-invalid="!!fields.title" :aria-describedby="fields.title ? 'announcement-title-error' : undefined">
          </label><p v-if="fields.title" id="announcement-title-error" class="feedback--error">{{ fields.title }}</p>
          <label class="editor-field" for="announcement-body">正文（最多 20000 字符）
            <textarea id="announcement-body" v-model="draft.body" maxlength="20000" rows="10" :disabled="loading || busy || unresolved || !writable || !editable" :aria-invalid="!!fields.body" :aria-describedby="fields.body ? 'announcement-body-error' : undefined" />
          </label><p v-if="fields.body" id="announcement-body-error" class="feedback--error">{{ fields.body }}</p>
          <p v-if="dirty" role="status">有尚未保存的公告输入。</p>
          <div v-if="conflict && panel === 'create'" class="feedback feedback--warning">
            <p>请先核对当前课程是否可写，输入仍保留；不会自动再次发布。</p>
            <button type="button" :disabled="!writable || loading || busy || unresolved" @click="conflict = false">已核对课程状态，允许重新提交</button>
          </div>
          <div v-if="conflict && selected" class="feedback feedback--warning">
            <p>当前服务端 revision {{ selected.revision }} · {{ selected.status === 'ACTIVE' ? '已发布' : '已撤回' }}</p>
            <strong>{{ selected.title }}</strong><p class="announcement-body" tabindex="0" aria-label="当前服务端正文">{{ selected.body }}</p>
            <button v-if="selected.status === 'ACTIVE'" type="button" :disabled="!writable || loading || busy || unresolved" @click="adoptRevision">采用最新 revision，保留我的输入</button>
          </div>
          <p v-if="selected?.status === 'WITHDRAWN'">公告已撤回，只能查看历史；不能改版或恢复。你的未保存输入仍保留。</p>
          <div class="announcement-actions"><button class="button button--primary" type="submit" :disabled="!canSend || !editable">{{ busy ? '提交中…' : panel === 'create' ? '确认立即发布' : '确认发布改版' }}</button>
            <button type="button" @click="navigate({ panel: undefined, announcementId: undefined })">取消编辑</button></div>
        </form>
        <p v-else-if="panel === 'edit' && selected?.status === 'WITHDRAWN'">公告已撤回，只能查看历史；不能改版或恢复。</p>
        <div v-if="panel === 'list' && selected?.status === 'ACTIVE'">
          <h3>撤回公告：{{ selected.title }}</h3><p>撤回 revision {{ selected.revision }} 后，学生旧链接不可读，历史版本仍保留。</p>
          <button v-if="!withdrawConfirm" type="button" :disabled="!canSend" @click="withdrawConfirm = true; conflict = false">撤回这条公告</button>
          <div v-else role="group" aria-label="二次确认撤回"><p>确定立即撤回？此处不能恢复已撤回公告。</p>
            <button class="button button--danger" type="button" :disabled="!canSend" @click="send('withdraw')">确认撤回</button>
            <button type="button" :disabled="busy" @click="withdrawConfirm = false">取消撤回</button></div>
          <button v-if="conflict" type="button" :disabled="!writable || loading || busy || unresolved" @click="conflict = false; withdrawConfirm = false">已核对最新版本，重新选择撤回</button>
        </div>
        <div v-if="panel === 'audit' && selected">
          <h3>只读审计：{{ selected.title }}</h3><p v-if="auditLoading" role="status">正在读取历史版本…</p>
          <p v-if="auditError" class="feedback feedback--error" role="alert">{{ auditError }} <button type="button" :disabled="cooling || auditLoading" @click="loadAudit">重试审计</button></p>
          <p v-if="audit && !audit.items.length">这一页暂无历史版本。</p>
          <article v-for="version in audit?.items ?? []" :key="version.revision" class="announcement-card">
            <p class="announcement-meta">revision {{ version.revision }} · {{ version.status === 'ACTIVE' ? '已发布' : '已撤回' }} · {{ new Date(version.changedAt).toLocaleString('zh-CN') }}</p>
            <h4>{{ version.title }}</h4><p class="announcement-body" tabindex="0" aria-label="审计正文">{{ version.body }}</p>
          </article>
          <nav v-if="audit" class="pagination" aria-label="审计版本分页">
            <button type="button" :disabled="auditPage <= 1 || auditLoading || cooling" @click="navigate({ auditPage: String(auditPage - 1) })">上一页</button>
            <span>第 {{ auditPage }} / {{ auditPages }} 页</span>
            <button type="button" :disabled="auditPage >= auditPages || auditLoading || cooling" @click="navigate({ auditPage: String(auditPage + 1) })">下一页</button>
          </nav>
        </div>
      </section>
    </div>
    <p v-if="writeError" class="feedback feedback--error" role="alert">{{ writeError }}</p>
    <ul v-if="Object.keys(fields).some(key => !['title', 'body'].includes(key))" class="feedback feedback--error" role="alert">
      <template v-for="(reason, key) in fields" :key="key"><li v-if="!['title', 'body'].includes(String(key))">{{ key }}：{{ reason }}</li></template>
    </ul>
  </section>
</template>

<style scoped>
.announcements-page { min-width: 0; }
.announcement-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 24px; align-items: start; }
.announcement-layout > :only-child { grid-column: 1 / -1; }
.announcement-card, .announcement-detail { padding: 20px; background: #fff; border: 1px solid #d6dfeb; border-radius: 12px; margin-bottom: 16px; min-width: 0; }
.announcement-card.is-selected { border-color: #2866bd; }
.announcement-card h3, .announcement-detail h3 { margin: 8px 0; overflow-wrap: anywhere; }
.announcement-body { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 320px; overflow-y: auto; }
.announcement-meta { color: #56667e; font-size: 13px; overflow-wrap: anywhere; }
.announcement-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.announcement-detail .editor-field { display: grid; gap: 8px; margin: 16px 0; }
.announcement-detail input, .announcement-detail textarea { width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid #97a8c0; border-radius: 6px; font: inherit; }
.announcement-detail textarea { resize: vertical; }
.pagination { flex-wrap: wrap; }
button:focus-visible, a:focus-visible, input:focus-visible, textarea:focus-visible { outline: 3px solid #2866bd; outline-offset: 3px; }
@media (max-width: 800px) { .announcement-layout { grid-template-columns: minmax(0, 1fr); gap: 12px; } .announcement-card, .announcement-detail { padding: 16px; } }
</style>
