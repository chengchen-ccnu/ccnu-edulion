<script setup lang="ts">
/** 展示服务端日/月趋势点。输入为 OpenAPI 趋势投影；图形只缩放像素，不汇总或改写统计事实。 */
import { computed } from "vue";
import type { LearningTrendData } from "../learning-analytics-api";
import { duration } from "../learning-analytics-state";
const props = defineProps<{ trend: LearningTrendData }>();
const maximum = computed(() => Math.max(1, ...props.trend.points.map(point => point.watchedSeconds)));
</script>

<template>
  <section class="analytics-trend" aria-label="学习活动趋势">
    <p v-if="!trend.points.length" class="teacher-empty">当前时间范围暂无已落库的活动趋势。</p>
    <template v-else>
      <div class="analytics-trend__bars" aria-hidden="true">
        <div v-for="point in trend.points" :key="point.periodStart" :title="`${point.periodStart}：${duration(point.watchedSeconds)}`">
          <span :style="{ height: `${Math.max(2, point.watchedSeconds / maximum * 100)}%` }"></span>
        </div>
      </div>
      <div class="analytics-table-wrap"><table class="analytics-table"><caption>服务端{{ trend.period.granularity === 'MONTH' ? '月' : '日' }}趋势明细</caption>
        <thead><tr><th scope="col">期间</th><th scope="col">观看时长</th><th scope="col">活跃学生</th><th scope="col">首次开始课时</th><th scope="col">完成课时</th><th scope="col">正式提交</th><th scope="col">提交学生</th><th scope="col">自动结算提交</th></tr></thead>
        <tbody><tr v-for="point in trend.points" :key="point.periodStart"><th scope="row">{{ point.periodStart }}<span v-if="point.periodEnd !== point.periodStart"> 至 {{ point.periodEnd }}</span></th>
          <td>{{ duration(point.watchedSeconds) }}</td><td>{{ point.activeStudentCount }}</td><td>{{ point.firstStartedLessons }}</td><td>{{ point.completedLessons }}</td><td>{{ point.formalSubmissionCount }}</td><td>{{ point.distinctSubmittingStudents }}</td><td>{{ point.autoSettledSubmissionCount }}</td></tr></tbody>
      </table></div>
    </template>
  </section>
</template>
