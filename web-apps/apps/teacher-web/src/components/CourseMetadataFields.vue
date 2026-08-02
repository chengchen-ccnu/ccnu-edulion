<script setup lang="ts">
/**
 * 教师课程创建/编辑共享的元数据字段组；props 接收 OpenAPI 请求字段和分类，update:* emits 回写表单。
 * 组件只负责输入与展示，不调用 API、不判断教师权限，也不持久化封面 URL。
 */
import type { CategorySummary, CourseAccessType, CourseLevel } from "@edu/api-client";

defineProps<{
  categories: CategorySummary[];
  fieldErrors?: Record<string, string>;
}>();

const title = defineModel<string>("title", { required: true });
const summary = defineModel<string>("summary", { required: true });
const description = defineModel<string>("description", { required: true });
const instructorDisplayName = defineModel<string>("instructorDisplayName", { required: true });
const categoryId = defineModel<string>("categoryId", { required: true });
const level = defineModel<CourseLevel>("level", { required: true });
const accessType = defineModel<CourseAccessType>("accessType", { required: true });
</script>

<template>
  <div class="editor-grid">
    <label class="editor-field editor-field--wide">
      <span>课程标题 <em>*</em></span>
      <input v-model="title" required maxlength="120" placeholder="例如：七年级代数基础">
      <small v-if="fieldErrors?.title" class="field-error">{{ fieldErrors.title }}</small>
    </label>
    <label class="editor-field editor-field--wide">
      <span>课程摘要</span>
      <textarea v-model="summary" maxlength="300" rows="3" placeholder="用一两句话说明课程能够帮助学生掌握什么" />
      <small v-if="fieldErrors?.summary" class="field-error">{{ fieldErrors.summary }}</small>
    </label>
    <label class="editor-field editor-field--wide">
      <span>课程介绍</span>
      <textarea v-model="description" maxlength="20000" rows="8" placeholder="支持保存 Markdown 文本；学生端按安全纯文本展示" />
      <small v-if="fieldErrors?.description" class="field-error">{{ fieldErrors.description }}</small>
    </label>
    <label class="editor-field">
      <span>讲师展示名 <em>*</em></span>
      <input v-model="instructorDisplayName" required maxlength="80">
      <small v-if="fieldErrors?.instructorDisplayName" class="field-error">{{ fieldErrors.instructorDisplayName }}</small>
    </label>
    <label class="editor-field">
      <span>课程分类 <em>*</em></span>
      <select v-model="categoryId" required>
        <option disabled value="">请选择启用分类</option>
        <option v-for="category in categories" :key="category.id" :value="category.id">{{ category.name }}</option>
      </select>
      <small v-if="fieldErrors?.categoryId" class="field-error">{{ fieldErrors.categoryId }}</small>
    </label>
    <label class="editor-field">
      <span>课程难度 <em>*</em></span>
      <select v-model="level" required>
        <option value="BEGINNER">入门</option>
        <option value="INTERMEDIATE">进阶</option>
        <option value="ADVANCED">高级</option>
      </select>
    </label>
    <label class="editor-field">
      <span>访问方式 <em>*</em></span>
      <select v-model="accessType" required>
        <option value="PUBLIC">公开课程</option>
        <option value="ENROLLMENT_REQUIRED">需报名访问</option>
      </select>
      <small>这里只保存访问规则；不实现报名流程。</small>
    </label>
  </div>
</template>
