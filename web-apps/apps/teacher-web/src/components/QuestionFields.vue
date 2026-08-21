<script setup lang="ts">
/**
 * 结构化题目字段组件；modelValue 是 OpenAPI QuestionVersionInput/QuestionChildInput，父题可含一层 children。
 * 题型变化会同步清理不适用的选项/子题；媒体控件只回写 READY mediaId，并向父页面汇总上传忙状态。
 */
import type {
  KnowledgePoint,
  QuestionChildInput,
  QuestionGrade,
  QuestionInputType,
  QuestionMediaPurpose,
  QuestionVersionInput
} from "@edu/api-client";
import { computed, ref, watch } from "vue";

import { emptyQuestionChild, gradesForStage } from "../question-form";
import MediaUploadField from "./MediaUploadField.vue";
import KnowledgePointPicker from "./KnowledgePointPicker.vue";
import SafeRichText from "./SafeRichText.vue";

defineOptions({ name: "QuestionFields" });
type EditorValue = QuestionVersionInput | QuestionChildInput;
const props = defineProps<{
  modelValue: EditorValue;
  knowledgePoints: KnowledgePoint[];
  fieldErrors: Record<string, string>;
  fieldPrefix: string;
  checkpointPrefix: string;
  allowComposite?: boolean;
  legend?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: EditorValue]; "busy-change": [value: boolean] }>();
const preview = ref(false);
const localBusy = ref<Record<string, boolean>>({});
const version = computed({ get: () => props.modelValue, set: value => emit("update:modelValue", value) });
const choice = computed(() => version.value.type === "SINGLE_CHOICE" || version.value.type === "MULTIPLE_CHOICE");
const children = computed(() => "children" in version.value ? version.value.children ?? [] : []);
const gradeOptions = computed(() => gradesForStage(version.value.stage));
const gradeLabels: Record<QuestionGrade, string> = { ONE: "一年级", TWO: "二年级", THREE: "三年级",
  FOUR: "四年级", FIVE: "五年级", SIX: "六年级", SEVEN: "七年级", EIGHT: "八年级", NINE: "九年级" };

watch(() => version.value.type, type => {
  const next = { ...version.value } as QuestionVersionInput;
  if (type === "SINGLE_CHOICE" || type === "MULTIPLE_CHOICE") {
    next.options = next.options.length >= 2 ? next.options : [{ key: "A", content: "" }, { key: "B", content: "" }];
    next.answers = next.answers.filter(answer => next.options.some(option => option.key === answer));
    if (!next.answers.length) next.answers = ["A"];
    next.children = undefined;
  } else {
    next.options = [];
    if (type === "TRUE_FALSE") next.answers = [next.answers[0] === "FALSE" ? "FALSE" : "TRUE"];
    else if (!next.answers.length || ["TRUE", "FALSE"].includes(next.answers[0])) next.answers = [""];
    if (type === "COMPOSITE") next.children = next.children?.length ? next.children
      : [emptyQuestionChild("SINGLE_CHOICE", next.stage, next.grade)];
    else next.children = undefined;
  }
  version.value = next;
});

function replace(patch: Partial<EditorValue>) { version.value = { ...version.value, ...patch } as EditorValue; }
function setStage(stage: QuestionVersionInput["stage"]) {
  const grade = gradesForStage(stage)[0];
  const patch: Partial<QuestionVersionInput> = { stage, grade, knowledgePointIds: [] };
  if ("children" in version.value && version.value.children) patch.children = version.value.children.map(child => ({
    ...child, stage, grade, knowledgePointIds: []
  }));
  replace(patch);
}
function setGrade(grade: QuestionVersionInput["grade"]) {
  const patch: Partial<QuestionVersionInput> = { grade, knowledgePointIds: [] };
  if ("children" in version.value && version.value.children) patch.children = version.value.children.map(child => ({
    ...child, grade, knowledgePointIds: []
  }));
  replace(patch);
}
function addOption() {
  if (version.value.options.length >= 8) return;
  const key = String.fromCharCode(65 + version.value.options.length);
  replace({ options: [...version.value.options, { key, content: "" }] });
}
function updateOption(index: number, content: string) {
  replace({ options: version.value.options.map((item, at) => at === index ? { ...item, content } : item) });
}
function removeOption(index: number) {
  const options = version.value.options.filter((_item, at) => at !== index)
    .map((item, at) => ({ ...item, key: String.fromCharCode(65 + at) }));
  replace({ options, answers: version.value.answers.filter(answer => options.some(item => item.key === answer)) });
}
function toggleChoiceAnswer(key: string, checked: boolean) {
  const answers = version.value.type === "SINGLE_CHOICE" ? [key]
    : checked ? [...new Set([...version.value.answers, key])] : version.value.answers.filter(item => item !== key);
  replace({ answers });
}
function setTextAnswers(value: string) { replace({ answers: value.split("\n") }); }
function mediaId(purpose: QuestionMediaPurpose): string | null {
  return version.value.media.find(item => item.purpose === purpose)?.mediaId ?? null;
}
function setMedia(purpose: QuestionMediaPurpose, mediaId: string | null) {
  const retained = version.value.media.filter(item => item.purpose !== purpose);
  replace({ media: [...retained, ...(mediaId ? [{ mediaId, purpose, sequence: retained.length + 1 }] : [])] });
}
function addChild() {
  if (!("children" in version.value) || children.value.length >= 100) return;
  replace({ children: [...children.value, emptyQuestionChild("SINGLE_CHOICE", version.value.stage, version.value.grade)] });
}
function replaceChild(index: number, child: EditorValue) {
  if (!("children" in version.value) || child.type === "COMPOSITE") return;
  replace({ children: children.value.map((item, at) => at === index ? {
    ...child, stage: version.value.stage, grade: version.value.grade, subject: version.value.subject
  } as QuestionChildInput : item) });
}
function removeChild(index: number) {
  if (!("children" in version.value) || children.value.length <= 1) return;
  replace({ children: children.value.filter((_item, at) => at !== index) });
}
function fieldError(name: string): string {
  return props.fieldErrors[`${props.fieldPrefix}.${name}`] ?? props.fieldErrors[name] ?? "";
}
function busy(key: string, value: boolean) {
  localBusy.value = { ...localBusy.value, [key]: value };
  emit("busy-change", Object.values(localBusy.value).some(Boolean));
}
</script>

<template>
  <fieldset class="question-fields">
    <legend v-if="legend">{{ legend }}</legend>
    <div class="question-fields__grid">
      <label class="editor-field"><span>题型 <em>*</em></span>
        <select :value="version.type" @change="replace({ type: ($event.target as HTMLSelectElement).value as QuestionVersionInput['type'] })">
          <option value="SINGLE_CHOICE">单选题</option><option value="MULTIPLE_CHOICE">多选题</option>
          <option value="TRUE_FALSE">判断题</option><option value="FILL_BLANK">填空题</option>
          <option value="SHORT_ANSWER">简答题</option><option v-if="allowComposite" value="COMPOSITE">组合题</option>
        </select><small v-if="fieldError('type')" class="field-error">{{ fieldError("type") }}</small>
      </label>
      <label class="editor-field"><span>难度 <em>*</em></span>
        <select :value="version.difficulty" @change="replace({ difficulty: ($event.target as HTMLSelectElement).value as QuestionVersionInput['difficulty'] })">
          <option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option>
        </select>
      </label>
      <label class="editor-field"><span>学段 <em>*</em></span>
        <select :value="version.stage" @change="setStage(($event.target as HTMLSelectElement).value as QuestionVersionInput['stage'])">
          <option value="PRIMARY">小学</option><option value="JUNIOR">初中</option>
        </select><small v-if="fieldError('stage')" class="field-error">{{ fieldError("stage") }}</small>
      </label>
      <label class="editor-field"><span>年级 <em>*</em></span>
        <select :value="version.grade" @change="setGrade(($event.target as HTMLSelectElement).value as QuestionVersionInput['grade'])">
          <option v-for="grade in gradeOptions" :key="grade" :value="grade">{{ gradeLabels[grade] }}</option>
        </select>
      </label>
      <label class="editor-field editor-field--wide"><span>题干 <em>*</em></span>
        <textarea :value="version.stem" rows="6" placeholder="支持 Markdown、$...$ 与 $$...$$ 公式"
          @input="replace({ stem: ($event.target as HTMLTextAreaElement).value })" />
        <small v-if="fieldError('stem')" class="field-error">{{ fieldError("stem") }}</small>
      </label>
    </div>

    <section v-if="choice" class="question-options">
      <header><strong>选项</strong><button type="button" :disabled="version.options.length >= 8" @click="addOption">添加选项</button></header>
      <div v-for="(option, index) in version.options" :key="option.key" class="question-option-row">
        <label><input :type="version.type === 'SINGLE_CHOICE' ? 'radio' : 'checkbox'" :name="`${checkpointPrefix}-answer`"
          :checked="version.answers.includes(option.key)" @change="toggleChoiceAnswer(option.key, ($event.target as HTMLInputElement).checked)">
          <span>{{ option.key }}</span></label>
        <input :value="option.content" :aria-label="`选项 ${option.key}`" @input="updateOption(index, ($event.target as HTMLInputElement).value)">
        <button type="button" :disabled="version.options.length <= 2" @click="removeOption(index)">移除</button>
      </div>
      <small v-if="fieldError('answers')" class="field-error">{{ fieldError("answers") }}</small>
    </section>
    <label v-else-if="version.type === 'TRUE_FALSE'" class="editor-field question-answer"><span>正确答案 <em>*</em></span>
      <select :value="version.answers[0]" @change="replace({ answers: [($event.target as HTMLSelectElement).value] })">
        <option value="TRUE">正确</option><option value="FALSE">错误</option>
      </select>
    </label>
    <label v-else class="editor-field question-answer"><span>{{ version.type === "COMPOSITE" ? "父题答案/说明" : "答案" }} <em>*</em></span>
      <textarea :value="version.answers.join('\n')" rows="3" placeholder="多空答案请每行填写一项" @input="setTextAnswers(($event.target as HTMLTextAreaElement).value)" />
      <small v-if="fieldError('answers')" class="field-error">{{ fieldError("answers") }}</small>
    </label>

    <label class="editor-field question-analysis"><span>解析</span>
      <textarea :value="version.analysis" rows="5" placeholder="支持 Markdown 与 LaTeX" @input="replace({ analysis: ($event.target as HTMLTextAreaElement).value })" />
      <small v-if="fieldError('analysis')" class="field-error">{{ fieldError("analysis") }}</small>
    </label>
    <KnowledgePointPicker :model-value="version.knowledgePointIds" :seed="knowledgePoints" :stage="version.stage"
      @update:model-value="replace({ knowledgePointIds: $event })" />
    <section class="question-media-grid" aria-label="题目媒体">
      <MediaUploadField :model-value="mediaId('QUESTION_IMAGE')" purpose="QUESTION_IMAGE" accept="image/jpeg,image/png,image/webp,image/gif"
        label="题目图片" :checkpoint-key="`${checkpointPrefix}:image`" @update:model-value="setMedia('QUESTION_IMAGE', $event)" @busy-change="busy('image', $event)" />
      <MediaUploadField :model-value="mediaId('QUESTION_AUDIO')" purpose="QUESTION_AUDIO" accept="audio/mpeg,audio/wav,audio/ogg"
        label="题目音频" :checkpoint-key="`${checkpointPrefix}:audio`" @update:model-value="setMedia('QUESTION_AUDIO', $event)" @busy-change="busy('audio', $event)" />
      <MediaUploadField :model-value="mediaId('QUESTION_ATTACHMENT')" purpose="QUESTION_ATTACHMENT" accept=".pdf,.doc,.docx,.txt,.zip"
        label="题目附件" :checkpoint-key="`${checkpointPrefix}:attachment`" @update:model-value="setMedia('QUESTION_ATTACHMENT', $event)" @busy-change="busy('attachment', $event)" />
    </section>

    <section v-if="version.type === 'COMPOSITE'" class="question-children">
      <header><div><strong>有序子题</strong><small>仅允许一层；每个子题独立提交完整结构</small></div>
        <button type="button" @click="addChild">添加子题</button></header>
      <article v-for="(child, index) in children" :key="index">
        <div class="question-child-heading"><strong>第 {{ index + 1 }} 题</strong>
          <button type="button" :disabled="children.length <= 1" @click="removeChild(index)">移除</button></div>
        <QuestionFields :model-value="child" :knowledge-points="knowledgePoints" :field-errors="fieldErrors"
          :field-prefix="`${fieldPrefix}.children[${index}]`" :checkpoint-prefix="`${checkpointPrefix}:child:${index}`"
          @update:model-value="replaceChild(index, $event)" @busy-change="busy(`child-${index}`, $event)" />
      </article>
      <small v-if="fieldError('children')" class="field-error">{{ fieldError("children") }}</small>
    </section>

    <section class="question-live-preview">
      <button type="button" @click="preview = !preview">{{ preview ? "收起预览" : "预览题干与解析" }}</button>
      <div v-if="preview"><strong>题干</strong><SafeRichText :source="version.stem" empty-text="尚未填写题干" />
        <strong>解析</strong><SafeRichText :source="version.analysis" empty-text="尚未填写解析" /></div>
    </section>
  </fieldset>
</template>
