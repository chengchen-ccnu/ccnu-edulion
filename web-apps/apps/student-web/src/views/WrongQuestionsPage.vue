<script setup lang="ts">
/**
 * 错题本：列表只使用 courseId 服务端筛选，详情和有限重做在当前页完成。
 * 掌握状态/重做都携带 revision 与稳定请求 ID；409 保留意图并重载最新详情，不重开原 attempt。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { WrongQuestion, WrongQuestionMasteryStatus, WrongQuestionPage, WrongQuestionRedoResult } from "@edu/api-client";
import { SafeAssessmentContent } from "@edu/ui";
import type { AssessmentMediaResolver } from "@edu/ui";
import { onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentAssessmentApi } from "../assessment-api";

const route = useRoute(); const router = useRouter();
const page = ref<WrongQuestionPage | null>(null); const selected = ref<WrongQuestion | null>(null);
const redoAnswer = ref(""); const redoSelections = ref<string[]>([]); const latestRedo = ref<WrongQuestionRedoResult | null>(null);
const intendedMastery = ref<WrongQuestionMasteryStatus | null>(null);
const loading = ref(false); const detailLoading = ref(false); const busy = ref(false); const errorMessage = ref("");
let controller: AbortController | null = null;

watch(() => route.query, load, { immediate: true });
onBeforeUnmount(() => controller?.abort());

async function load() {
  controller?.abort(); controller = new AbortController(); loading.value = true; errorMessage.value = "";
  try { page.value = await studentAssessmentApi.wrongQuestions({ page: positive(route.query.page), size: 12,
    courseId: typeof route.query.courseId === "string" && route.query.courseId ? route.query.courseId : undefined }, controller.signal); }
  catch (error) { if (!controller.signal.aborted) errorMessage.value = messageFor(error); }
  finally { if (!controller.signal.aborted) loading.value = false; }
}
async function open(item: WrongQuestion) {
  detailLoading.value = true; errorMessage.value = ""; latestRedo.value = null;
  try { selected.value = await studentAssessmentApi.wrongQuestion(item.id, controller?.signal); redoAnswer.value = ""; redoSelections.value = []; }
  catch (error) { errorMessage.value = messageFor(error); }
  finally { detailLoading.value = false; }
}
async function changeMastery(status: WrongQuestionMasteryStatus) {
  if (!selected.value || busy.value) return;
  intendedMastery.value = status; busy.value = true; errorMessage.value = "";
  try {
    selected.value = await studentAssessmentApi.updateMastery(selected.value.id, {
      clientRequestId: crypto.randomUUID(), expectedRevision: selected.value.revision, masteryStatus: status
    }, controller?.signal);
    intendedMastery.value = null; replaceListItem(selected.value);
  } catch (error) {
    if (error instanceof GatewayRequestError && error.status === 409) {
      try { selected.value = await studentAssessmentApi.wrongQuestion(selected.value.id, controller?.signal); replaceListItem(selected.value); }
      catch (reloadError) { errorMessage.value = messageFor(reloadError); }
      errorMessage.value ||= "掌握状态已变化。你的操作意图已保留，请确认后按最新 revision 重试。";
    } else errorMessage.value = messageFor(error);
  } finally { busy.value = false; }
}
async function redo() {
  if (!selected.value || busy.value || !selected.value.redoAvailable) return;
  const choice = ["SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE"].includes(selected.value.question.type);
  const answer = choice ? [...redoSelections.value] : selected.value.question.type === "COMPOSITE" || selected.value.question.type === "FILL_BLANK"
    ? redoAnswer.value.split(/\r?\n/).map(item => item.trim()).filter(Boolean) : [redoAnswer.value.trim()].filter(Boolean);
  if (!answer.length) { errorMessage.value = "请先填写本次重做答案。"; return; }
  busy.value = true; errorMessage.value = "";
  const body = { clientRequestId: crypto.randomUUID(), expectedRevision: selected.value.revision, answer };
  try {
    latestRedo.value = await studentAssessmentApi.redo(selected.value.id, body, controller?.signal);
    selected.value = await studentAssessmentApi.wrongQuestion(selected.value.id, controller?.signal);
    replaceListItem(selected.value); redoAnswer.value = ""; redoSelections.value = [];
  } catch (error) {
    if (error instanceof GatewayRequestError && error.status === 409) {
      selected.value = await studentAssessmentApi.wrongQuestion(selected.value.id, controller?.signal);
      replaceListItem(selected.value);
      errorMessage.value = "错题 revision 或剩余次数已变化；答案已保留，请核对最新状态后重试。";
    } else errorMessage.value = messageFor(error);
  } finally { busy.value = false; }
}
function replaceListItem(item: WrongQuestion) { if (page.value) page.value.items = page.value.items.map(existing => existing.id === item.id ? item : existing); }
function toggleRedoSelection(key: string, multiple: boolean, checked: boolean) {
  if (!multiple) { redoSelections.value = checked ? [key] : []; return; }
  const values = new Set(redoSelections.value); checked ? values.add(key) : values.delete(key);
  redoSelections.value = [...values].sort();
}
function setQuery(name: string, value: string | number) { void router.replace({ query: { ...route.query, [name]: String(value), page: name === "page" ? String(value) : "1" } }); }
const resolveMedia: AssessmentMediaResolver = async (mediaId, purpose, signal) => {
  if (!selected.value) throw new Error("WRONG_QUESTION_NOT_SELECTED");
  return studentAssessmentApi.mediaGrant(selected.value.assessmentId, mediaId,
    purpose === "QUESTION_ATTACHMENT" ? "ATTACHMENT" : "INLINE", signal);
};
function positive(value: unknown) { const number = Number(value); return Number.isInteger(number) && number > 0 ? number : 1; }
function messageFor(error: unknown) {
  if (error instanceof TypeError) return "当前离线，恢复网络后可重试；已读取内容仍保留。";
  if (!(error instanceof GatewayRequestError)) return "错题服务暂时不可用。";
  if (error.status === 401) return "登录状态已失效。";
  if (error.status === 403 || error.status === 404) return "错题不存在或不可见。";
  if (error.status === 409) return "错题状态或 revision 已变化。";
  if (error.status === 422) return error.message;
  if (error.status === 429) return "请求过于频繁，请稍后重试。";
  return error.message;
}
</script>

<template>
  <main class="wrong-page"><header class="practice-heading"><div><RouterLink to="/practice">← 返回题库</RouterLink><h2>错题本</h2>
    <p>重做不会回到原测评，也不会修改原作答。</p></div></header>
    <form class="practice-filters" @submit.prevent><label>课程 ID<input :value="route.query.courseId ?? ''" placeholder="可选 UUID"
      @change="setQuery('courseId', ($event.target as HTMLInputElement).value.trim())"></label><button type="button" @click="load">重新载入</button></form>
    <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
    <div v-if="loading && !page" class="practice-loading" role="status">正在读取错题…</div>
    <div v-else class="wrong-layout"><section><article v-for="item in page?.items ?? []" :key="item.id" class="wrong-card">
      <header><strong>{{ item.question.type }}</strong><span>{{ item.masteryStatus === 'MASTERED' ? '已掌握' : '学习中' }}</span></header>
      <SafeAssessmentContent :source="item.question.stem" :media="item.question.media" />
      <footer><span>累计错 {{ item.wrongCount }} 次 · 重做 {{ item.redoAttemptsUsed }}/{{ item.redoAttemptsLimit }}</span>
        <button type="button" @click="open(item)">在本页查看与重做</button></footer></article>
      <section v-if="page && !page.items.length" class="practice-empty"><h3>暂无错题</h3><p>错题由服务端在真实提交后归档。</p></section>
      <nav v-if="page && page.total > page.size" class="pagination"><button :disabled="page.page <= 1" @click="setQuery('page', page.page - 1)">上一页</button>
        <span>第 {{ page.page }} 页</span><button :disabled="page.page * page.size >= page.total" @click="setQuery('page', page.page + 1)">下一页</button></nav></section>
      <aside v-if="detailLoading" class="wrong-detail" role="status">正在读取错题详情…</aside>
      <aside v-else-if="selected" class="wrong-detail" aria-label="错题详情"><header><h3>错题详情</h3><button type="button" @click="selected = null">关闭</button></header>
        <SafeAssessmentContent :source="selected.question.stem" :media="selected.question.media"
          :mark-fill-blanks="selected.question.type === 'FILL_BLANK'" :resolve-media="resolveMedia" />
        <section v-for="child in selected.question.children" :key="child.versionId"><h4>({{ child.sequence }})</h4>
          <SafeAssessmentContent :source="child.stem" :media="child.media" :resolve-media="resolveMedia" /></section>
        <div class="mastery-actions"><span>掌握状态：{{ selected.masteryStatus === 'MASTERED' ? '已掌握' : '学习中' }}</span>
          <button type="button" :disabled="busy" @click="changeMastery(selected.masteryStatus === 'MASTERED' ? 'LEARNING' : 'MASTERED')">切换状态</button>
          <button v-if="intendedMastery" type="button" :disabled="busy" @click="changeMastery(intendedMastery)">按最新版本重试“{{ intendedMastery === 'MASTERED' ? '已掌握' : '学习中' }}”</button></div>
        <form class="redo-form" @submit.prevent="redo">
          <fieldset v-if="selected.question.type === 'SINGLE_CHOICE' || selected.question.type === 'TRUE_FALSE'" :disabled="busy || !selected.redoAvailable"><legend>本次重做答案</legend>
            <label v-for="option in selected.question.type === 'TRUE_FALSE' ? [{ key: 'TRUE', content: '正确' }, { key: 'FALSE', content: '错误' }] : selected.question.options" :key="option.key">
              <input type="radio" name="wrong-redo" :value="option.key" :checked="redoSelections[0] === option.key"
                @change="toggleRedoSelection(option.key, false, true)"><strong>{{ option.key }}</strong><SafeAssessmentContent :source="option.content" :media="selected.question.media" :resolve-media="resolveMedia" /></label></fieldset>
          <fieldset v-else-if="selected.question.type === 'MULTIPLE_CHOICE'" :disabled="busy || !selected.redoAvailable"><legend>本次重做答案（可多选）</legend>
            <label v-for="option in selected.question.options" :key="option.key"><input type="checkbox" :checked="redoSelections.includes(option.key)"
              @change="toggleRedoSelection(option.key, true, ($event.target as HTMLInputElement).checked)"><strong>{{ option.key }}</strong><SafeAssessmentContent :source="option.content" :media="selected.question.media" :resolve-media="resolveMedia" /></label></fieldset>
          <label v-else>本次重做答案<textarea v-model="redoAnswer" rows="5" :disabled="busy || !selected.redoAvailable"
            :placeholder="selected.question.type === 'COMPOSITE' ? '各小问按行填写' : selected.question.type === 'FILL_BLANK' ? '多个空按行填写' : '填写答案'" /></label>
          <button class="button button--primary" type="submit" :disabled="busy || !selected.redoAvailable">{{ selected.redoAvailable ? `提交重做（剩余 ${selected.redoAttemptsLimit - selected.redoAttemptsUsed} 次）` : '已用完三次重做' }}</button></form>
        <section v-if="latestRedo" class="redo-result" role="status"><h4>本次结果</h4><p v-if="latestRedo.correct !== null">{{ latestRedo.correct ? '本次作答正确' : '本次仍需巩固' }}<span v-if="latestRedo.score !== null"> · {{ latestRedo.score }} 分</span></p>
          <p v-else>这是主观题，服务端无法可靠自动判定分数或对错；本次记录已保存并消耗一次重做机会。</p></section>
        <section><h4>重做历史</h4><ol v-if="selected.redoHistory.length"><li v-for="redoItem in selected.redoHistory" :key="redoItem.id">
          第 {{ redoItem.sequence }} 次 · {{ new Date(redoItem.submittedAt).toLocaleString('zh-CN') }} · {{ redoItem.correct === null ? '主观题未自动判定' : redoItem.correct ? '正确' : '错误' }}</li></ol><p v-else>尚未重做。</p></section>
      </aside></div>
  </main>
</template>
