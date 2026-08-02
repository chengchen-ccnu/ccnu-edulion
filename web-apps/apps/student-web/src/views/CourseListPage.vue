<script setup lang="ts">
/**
 * 学生端公开课程目录页；数据来自 GET /api/v1/courses 与 /courses/categories。
 * 筛选和页码与路由查询参数双向同步，成功显示公开课程，失败显示 Gateway 错误；本页无需登录权限。
 */
import { GatewayRequestError } from "@edu/api-client";
import type { CategorySummary, CourseAccessType, CourseLevel, CoursePage, ListCoursesQuery } from "@edu/api-client";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { studentCourseApi } from "../course-api";

const route = useRoute();
const router = useRouter();
const pageSize = 12;
const filters = reactive({ keyword: "", categoryId: "", level: "", accessType: "" });
const result = ref<CoursePage | null>(null);
const categories = ref<CategorySummary[]>([]);
const loading = ref(false);
const errorMessage = ref("");
let requestSequence = 0;

const pageCount = computed(() => Math.max(1, Math.ceil((result.value?.total ?? 0) / pageSize)));
const currentPage = computed(() => result.value?.page ?? pageFromRoute());
const hasFilters = computed(() => Object.values(filters).some(Boolean));

onMounted(async () => {
  try {
    categories.value = await studentCourseApi.categories();
  } catch (error) {
    errorMessage.value = messageFor(error, "课程分类暂时不可用");
  }
});

watch(() => route.fullPath, async () => {
  syncFiltersFromRoute();
  await loadCourses();
}, { immediate: true });

function syncFiltersFromRoute() {
  filters.keyword = textQuery("keyword");
  filters.categoryId = textQuery("categoryId");
  filters.level = textQuery("level");
  filters.accessType = textQuery("accessType");
}

async function applyFilters() {
  await router.push({ name: "courses", query: courseQuery(1) });
}

async function clearFilters() {
  Object.assign(filters, { keyword: "", categoryId: "", level: "", accessType: "" });
  await router.push({ name: "courses" });
}

async function changePage(page: number) {
  if (page < 1 || page > pageCount.value || page === currentPage.value) return;
  await router.push({ name: "courses", query: courseQuery(page) });
}

async function loadCourses() {
  const sequence = ++requestSequence;
  loading.value = true;
  errorMessage.value = "";
  const query: ListCoursesQuery = { page: pageFromRoute(), size: pageSize };
  if (filters.keyword) query.keyword = filters.keyword;
  if (filters.categoryId) query.categoryId = filters.categoryId;
  if (filters.level) query.level = filters.level as CourseLevel;
  if (filters.accessType) query.accessType = filters.accessType as CourseAccessType;
  try {
    const page = await studentCourseApi.list(query);
    if (sequence === requestSequence) result.value = page;
  } catch (error) {
    if (sequence === requestSequence) errorMessage.value = messageFor(error, "课程列表加载失败，请稍后重试");
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}

function courseQuery(page: number): Record<string, string> {
  const query: Record<string, string> = {};
  if (page > 1) query.page = String(page);
  if (filters.keyword.trim()) query.keyword = filters.keyword.trim();
  if (filters.categoryId) query.categoryId = filters.categoryId;
  if (filters.level) query.level = filters.level;
  if (filters.accessType) query.accessType = filters.accessType;
  return query;
}

function pageFromRoute(): number {
  const value = Number(textQuery("page"));
  return Number.isInteger(value) && value > 0 ? value : 1;
}

function textQuery(key: string): string {
  const value = route.query[key];
  return typeof value === "string" ? value : "";
}

function messageFor(error: unknown, fallback: string): string {
  return error instanceof GatewayRequestError ? error.message : fallback;
}

function levelLabel(level: CourseLevel): string {
  return { BEGINNER: "入门", INTERMEDIATE: "进阶", ADVANCED: "高级" }[level];
}

function accessLabel(accessType: CourseAccessType): string {
  return accessType === "PUBLIC" ? "公开课程" : "需报名访问";
}
</script>

<template>
  <section class="course-hero">
    <div>
      <span class="section-kicker">COURSE CATALOG</span>
      <h2>找到适合你的下一门课</h2>
      <p>按分类、难度与访问方式筛选已发布课程，目录内容来自当前线上版本。</p>
    </div>
    <div class="course-hero__stat">
      <strong>{{ result?.total ?? "—" }}</strong>
      <span>门可浏览课程</span>
    </div>
  </section>

  <form class="filter-panel" aria-label="课程筛选" @submit.prevent="applyFilters">
    <label class="filter-panel__search">
      <span>关键词</span>
      <input v-model="filters.keyword" maxlength="100" placeholder="搜索课程标题或简介">
    </label>
    <label>
      <span>分类</span>
      <select v-model="filters.categoryId">
        <option value="">全部分类</option>
        <option v-for="category in categories" :key="category.id" :value="category.id">{{ category.name }}</option>
      </select>
    </label>
    <label>
      <span>难度</span>
      <select v-model="filters.level">
        <option value="">全部难度</option>
        <option value="BEGINNER">入门</option>
        <option value="INTERMEDIATE">进阶</option>
        <option value="ADVANCED">高级</option>
      </select>
    </label>
    <label>
      <span>访问方式</span>
      <select v-model="filters.accessType">
        <option value="">全部方式</option>
        <option value="PUBLIC">公开课程</option>
        <option value="ENROLLMENT_REQUIRED">需报名访问</option>
      </select>
    </label>
    <div class="filter-panel__actions">
      <button class="button button--primary" type="submit">应用筛选</button>
      <button v-if="hasFilters" class="button button--quiet" type="button" @click="clearFilters">清除</button>
    </div>
  </form>

  <p v-if="errorMessage" class="feedback feedback--error" role="alert">{{ errorMessage }}</p>
  <div v-if="loading" class="loading-grid" aria-label="正在加载课程">
    <span v-for="item in 6" :key="item" />
  </div>
  <div v-else-if="result?.items.length" class="course-grid">
    <RouterLink v-for="course in result.items" :key="course.id"
      class="course-card" :to="{ name: 'course-detail', params: { courseId: course.id } }">
      <div class="course-card__cover">
        <img :src="course.coverUrl" :alt="`${course.title}封面`">
        <span>{{ course.category.name }}</span>
      </div>
      <div class="course-card__body">
        <div class="meta-row">
          <span>{{ levelLabel(course.level) }}</span>
          <span>{{ accessLabel(course.accessType) }}</span>
        </div>
        <h3>{{ course.title }}</h3>
        <p>{{ course.summary || "这门课程暂未填写摘要。" }}</p>
        <footer>
          <span>{{ course.teacher.displayName }}</span>
          <strong>查看目录 →</strong>
        </footer>
      </div>
    </RouterLink>
  </div>
  <div v-else-if="result" class="empty-state">
    <span>暂无匹配课程</span>
    <h3>换一组筛选条件试试</h3>
    <p>当前只展示已经发布并可公开浏览的课程信息与目录。</p>
    <button v-if="hasFilters" class="button button--primary" type="button" @click="clearFilters">查看全部课程</button>
  </div>

  <nav v-if="result && result.total > pageSize" class="pagination" aria-label="课程分页">
    <button type="button" :disabled="currentPage <= 1" @click="changePage(currentPage - 1)">上一页</button>
    <span>第 {{ currentPage }} / {{ pageCount }} 页</span>
    <button type="button" :disabled="currentPage >= pageCount" @click="changePage(currentPage + 1)">下一页</button>
  </nav>
</template>
