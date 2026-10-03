<script setup lang="ts">
/**
 * 匿名课程门户：分类及最多六张卡只读取既有公开投影。
 * 登录学习账号仅取一页三条ACTIVE报名，各卡详情/进度独立失败；不重算热点，不缓存短期封面。
 */
import type { CategorySummary, HotCourses, HotCourseCard } from "@edu/api-client";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { authStore } from "../auth";
import { studentCourseApi } from "../course-api";
import { studentLearningApi } from "../learning-api";
import { resumeLessonId } from "../learning-model";
import type { MyLearningItem } from "../learning-model";
import { operationsApi } from "../operations-api";
import { usePortalFailure } from "../portal-state";

const categories = ref<CategorySummary[]>([]);
const hot = ref<HotCourses | null>(null);
const learning = ref<MyLearningItem[]>([]);
const hotLoading = ref(false), categoryLoading = ref(false), learningLoading = ref(false);
const hotFailure = usePortalFailure(), categoryFailure = usePortalFailure(), learningFailure = usePortalFailure();
const hotError = hotFailure.error, categoryError = categoryFailure.error, learningError = learningFailure.error;
const failedCovers = ref(new Set<string>());
const now = ref(Date.now());
let hotController: AbortController | null = null, categoryController: AbortController | null = null;
let learningController: AbortController | null = null;
let expiryTimer: ReturnType<typeof setTimeout> | undefined;
const canLearn = computed(() => authStore.state.status === "authenticated" && authStore.hasPermission("learning:access"));
const expired = computed(() => !!hot.value?.validUntil && Date.parse(hot.value.validUntil) <= now.value);
const cards = computed(() => expired.value ? [] : hot.value?.items.slice(0, 6) ?? []);
const hotNote = computed(() => expired.value ? "本次推荐已到期，请刷新后查看。"
  : ({ EMPTY: "当前没有符合推荐条件的课程，去课程中心探索更多内容。", UNINITIALIZED: "课程推荐正在准备中，请稍后查看。",
    UNAVAILABLE: "课程推荐暂时不可用，分类和课程中心仍可使用。", READY: "" }[hot.value?.status ?? "READY"]));

/** 单个到期定时器只失效现有投影，不自动网络循环；恢复可见时有限刷新一次。 */
function scheduleExpiry() {
  clearTimeout(expiryTimer);
  now.value = Date.now();
  const dates = [hot.value?.validUntil, ...cards.value.map(card => card.coverExpiresAt)]
    .filter((value): value is string => !!value).map(Date.parse).filter(value => value > now.value);
  if (dates.length) expiryTimer = setTimeout(scheduleExpiry, Math.min(...dates) - now.value + 10);
}
/** 读取公开热榜原投影，失败清空旧榜；用户重试尊重限流，不从课程列表重排补卡。 */
async function loadHot() {
  if (!hotFailure.allowed() || hotLoading.value) return;
  hotController?.abort();
  const active = new AbortController(); hotController = active;
  hotLoading.value = true; hotError.value = ""; hot.value = null; failedCovers.value = new Set();
  try { const value = await operationsApi.hotCourses(active.signal); if (!active.signal.aborted) { hot.value = value; scheduleExpiry(); } }
  catch (error) { if (!active.signal.aborted) hotFailure.fail(error); }
  finally { if (!active.signal.aborted) hotLoading.value = false; }
}
/** 分类独立读取，故障不阻断热点/继续学习，离页取消后不更新结果。 */
async function loadCategories() {
  if (!categoryFailure.allowed() || categoryLoading.value) return;
  categoryController?.abort(); const active = new AbortController(); categoryController = active;
  categoryLoading.value = true; categoryError.value = "";
  try { const value = await studentCourseApi.categories(active.signal); if (!active.signal.aborted) categories.value = value; }
  catch (error) { if (!active.signal.aborted) categoryFailure.fail(error); }
  finally { if (!active.signal.aborted) categoryLoading.value = false; }
}
/** 有界读取本人三条报名；失败区域保留独立文案，进度失败不推断继续课时。 */
async function loadLearning() {
  learningController?.abort(); learning.value = []; learningError.value = ""; learningLoading.value = false;
  if (!canLearn.value || !learningFailure.allowed()) return;
  const active = new AbortController(); learningController = active; learningLoading.value = true;
  try {
    const page = await studentLearningApi.enrollments({ page: 1, size: 3, status: "ACTIVE" }, active.signal);
    const values = await Promise.all(page.items.slice(0, 3).map(async enrollment => {
      const [course, progress] = await Promise.allSettled([
        studentCourseApi.detail(enrollment.courseId, active.signal), studentLearningApi.progress(enrollment.courseId, active.signal)
      ]);
      return { enrollment, course: course.status === "fulfilled" ? course.value : null,
        progress: progress.status === "fulfilled" ? progress.value : null,
        courseError: course.status === "rejected" ? "课程信息暂不可用" : "",
        progressError: progress.status === "rejected" ? "进度暂不可用" : "" };
    }));
    if (!active.signal.aborted) learning.value = values;
  } catch (error) { if (!active.signal.aborted) learningFailure.fail(error); }
  finally { if (!active.signal.aborted) learningLoading.value = false; }
}
function continueHref(item: MyLearningItem) {
  if (!item.course || !item.progress) return `/courses/${item.enrollment.courseId}`;
  const lesson = resumeLessonId(item.course, item.progress, true);
  return lesson ? `/learn/${item.course.id}/${lesson}` : `/courses/${item.course.id}`;
}
function coverVisible(card: HotCourseCard) {
  return card.coverStatus === "READY" && !!card.coverUrl && !!card.coverExpiresAt
    && Date.parse(card.coverExpiresAt) > now.value && !failedCovers.value.has(card.courseId);
}
function visible() {
  if (document.hidden) return;
  scheduleExpiry();
  if (expired.value) void loadHot();
  if (canLearn.value) void loadLearning();
}
watch([canLearn, () => authStore.state.currentUser?.id], loadLearning, { immediate: true });
onMounted(() => { void loadHot(); void loadCategories(); document.addEventListener("visibilitychange", visible); });
onBeforeUnmount(() => {
  hotController?.abort(); categoryController?.abort(); learningController?.abort();
  clearTimeout(expiryTimer); document.removeEventListener("visibilitychange", visible);
});
</script>

<template>
  <div class="portal-home">
    <section class="portal-welcome" aria-labelledby="home-title">
      <div><span class="section-kicker">A LITTLE PROGRESS, EVERY DAY</span>
        <h2 id="home-title">让好奇心，<br>带你走得更远<span>。</span></h2>
        <p>发现一门好课，从一个小小的开始，积累自己的成长。</p>
        <RouterLink class="button button--primary" to="/courses">探索课程 <span aria-hidden="true">↗</span></RouterLink>
      </div>
      <div class="portal-motif" aria-hidden="true"><span class="motif-orbit"></span><span class="motif-square">a² + b²</span><span class="motif-disc">∑</span><span class="motif-caption">LEARN · EXPLORE · GROW</span></div>
    </section>
    <section class="portal-categories" aria-labelledby="categories-title">
      <header class="portal-heading"><div><span class="section-kicker">FIND YOUR DIRECTION</span><h3 id="categories-title">从感兴趣的方向开始</h3></div><RouterLink to="/courses">全部课程 →</RouterLink></header>
      <p v-if="categoryError" class="feedback feedback--warning" role="alert">{{ categoryError }} <button @click="loadCategories">重试分类</button></p>
      <div v-if="categoryLoading && !categories.length" class="portal-skeleton" role="status">正在加载分类…</div>
      <nav v-else-if="categories.length" class="category-chips" aria-label="课程分类"><RouterLink v-for="(category, index) in categories" :key="category.id" :to="{ path: '/courses', query: { categoryId: category.id } }"><span aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>{{ category.name }}<span aria-hidden="true">↗</span></RouterLink></nav>
      <p v-else-if="!categoryError" class="portal-empty">分类尚未开放，可直接浏览课程中心。</p>
    </section>
    <section aria-labelledby="hot-title">
      <header class="portal-heading"><div><span class="section-kicker">WORTH EXPLORING</span><h3 id="hot-title">值得探索的课程</h3><p>近期热门与新课，为下一次学习找一点灵感。</p></div><button class="button button--quiet" :disabled="hotLoading" @click="loadHot">刷新推荐</button></header>
      <p v-if="hotError" class="feedback feedback--warning" role="alert">{{ hotError }}</p>
      <div v-if="hotLoading" class="portal-course-grid" aria-label="正在加载推荐"><div v-for="n in 3" :key="n" class="portal-skeleton"></div></div>
      <div v-else-if="cards.length" class="portal-course-grid">
        <RouterLink v-for="card in cards" :key="card.courseId" class="portal-course-card" :to="`/courses/${card.courseId}`">
          <div class="portal-cover"><img v-if="coverVisible(card)" :src="card.coverUrl!" :alt="`${card.title}封面`" @error="failedCovers.add(card.courseId)">
            <div v-else class="portal-cover-fallback"><span aria-hidden="true">∑</span><small>{{ card.coverStatus === 'ABSENT' ? '暂无课程封面' : '封面暂不可用' }}</small></div>
            <span class="portal-course-label" :class="{ 'is-new': card.label === 'NEW' }">{{ card.label === 'NEW' ? '新课' : '近期热门' }}</span></div>
          <div class="portal-card-copy"><small>{{ { BEGINNER: '入门', INTERMEDIATE: '进阶', ADVANCED: '高级' }[card.level] }} · {{ card.accessType === 'PUBLIC' ? '公开课程' : '报名学习' }}</small><h4>{{ card.title }}</h4><p>{{ card.summary }}</p><footer><span>{{ card.instructorDisplayName }}</span><span aria-hidden="true">↗</span></footer></div>
        </RouterLink>
      </div>
      <div v-else-if="hotNote && !hotError" class="portal-empty" role="status"><h4>{{ hotNote }}</h4><RouterLink to="/courses">浏览课程中心 →</RouterLink></div>
    </section>
    <section v-if="canLearn" aria-labelledby="continue-title">
      <header class="portal-heading"><div><span class="section-kicker">KEEP YOUR MOMENTUM</span><h3 id="continue-title">接着上次，继续向前</h3><p>先看看已报名课程，完整学习记录随时可查。</p></div><RouterLink to="/my-learning">我的学习 →</RouterLink></header>
      <p v-if="learningError" class="feedback feedback--warning" role="alert">{{ learningError }} <button @click="loadLearning">重试学习记录</button></p>
      <div v-if="learningLoading" class="portal-skeleton" role="status">正在读取学习记录…</div>
      <div v-else-if="learning.length" class="portal-resume-grid"><article v-for="item in learning" :key="item.enrollment.id" class="portal-resume-card"><h4>{{ item.course?.title ?? '课程信息暂不可用' }}</h4><p v-if="item.courseError || item.progressError" role="status">{{ item.courseError || item.progressError }}，可稍后重试。</p><template v-if="item.progress"><p>已完成 {{ item.progress.completedLessonCount }} / {{ item.progress.lessonCount }} 课时</p><progress :value="item.progress.completionPercent" max="100" :aria-label="`课程完成度 ${item.progress.completionPercent}%`"></progress></template><RouterLink :to="continueHref(item)">{{ item.progress && item.course ? '继续学习' : '查看课程' }} →</RouterLink></article></div>
      <button v-if="learning.some(item => item.courseError || item.progressError)" class="button button--quiet" :disabled="learningLoading" @click="loadLearning">重试学习信息</button>
      <div v-else-if="!learningError" class="portal-empty">还没有报名课程，<RouterLink to="/courses">发现你的第一门好课 →</RouterLink></div>
    </section>
    <section v-else-if="authStore.state.status !== 'authenticated'" class="portal-signin"><div><h3>把每一步成长，留在这里</h3><p>登录后查看课程进度，接着上次继续学习。</p></div><RouterLink class="button button--quiet" :to="{ path: '/login', query: { redirect: '/' } }">登录账号 →</RouterLink></section>
  </div>
</template>
