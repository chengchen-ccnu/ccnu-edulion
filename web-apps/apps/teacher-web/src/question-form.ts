import type {
  KnowledgePoint,
  QuestionGrade,
  QuestionChildInput,
  QuestionInputType,
  QuestionVersion,
  QuestionVersionInput
} from "@edu/api-client";

export const PRIMARY_GRADES: QuestionGrade[] = ["ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX"];
export const JUNIOR_GRADES: QuestionGrade[] = ["SEVEN", "EIGHT", "NINE"];

/** 返回所选学段的合法年级，供筛选器和编辑器共享同一前端约束。 */
export function gradesForStage(stage: QuestionVersionInput["stage"]): QuestionGrade[] {
  return stage === "PRIMARY" ? PRIMARY_GRADES : JUNIOR_GRADES;
}

/** 创建满足 T09B 必填字段的空普通题，供新建和人工复核表单使用。 */
export function emptyQuestionVersion(type: QuestionVersionInput["type"] = "SINGLE_CHOICE"): QuestionVersionInput {
  return {
    type,
    difficulty: "MEDIUM",
    stage: "JUNIOR",
    grade: "SEVEN",
    subject: "MATHEMATICS",
    stem: "",
    options: type === "SINGLE_CHOICE" || type === "MULTIPLE_CHOICE"
      ? [{ key: "A", content: "" }, { key: "B", content: "" }] : [],
    answers: [type === "TRUE_FALSE" ? "TRUE" : ""],
    analysis: "",
    knowledgePointIds: [],
    media: [],
    ...(type === "COMPOSITE" ? { children: [emptyQuestionChild()] } : {})
  };
}

/** 创建一层组合题子题；子题类型受生成的 QuestionInputType 约束，不能继续嵌套 COMPOSITE。 */
export function emptyQuestionChild(type: QuestionInputType = "SINGLE_CHOICE",
    stage: QuestionVersionInput["stage"] = "JUNIOR",
    grade: QuestionVersionInput["grade"] = stage === "PRIMARY" ? "ONE" : "SEVEN"): QuestionChildInput {
  const base = emptyQuestionVersion(type);
  const { children: _children, ...child } = base;
  return { ...child, stage, grade } as QuestionChildInput;
}

/** 将只读版本投影转换为新的写契约副本；只复制业务字段，不复制 versionId、时间或 revision。 */
export function editableQuestionVersion(version: QuestionVersion): QuestionVersionInput {
  return {
    type: version.type,
    difficulty: version.difficulty,
    stage: version.stage,
    grade: version.grade,
    subject: version.subject,
    stem: version.stem,
    options: version.options.map(option => ({ key: option.key, content: option.content })),
    answers: [...version.answers],
    analysis: version.analysis,
    knowledgePointIds: version.knowledgePoints.map(point => point.id),
    media: version.media.map(item => ({ mediaId: item.mediaId, purpose: item.purpose, sequence: item.sequence })),
    ...(version.type === "COMPOSITE" ? {
      children: version.children.map(child => ({
        type: child.type,
        difficulty: child.difficulty,
        stage: child.stage,
        grade: child.grade,
        subject: child.subject,
        stem: child.stem,
        options: child.options.map(option => ({ key: option.key, content: option.content })),
        answers: [...child.answers],
        analysis: child.analysis,
        knowledgePointIds: child.knowledgePoints.map(point => point.id),
        media: child.media.map(item => ({ mediaId: item.mediaId, purpose: item.purpose, sequence: item.sequence }))
      }))
    } : {})
  };
}

/** 将知识点树扁平化用于筛选和多选控件；输入顺序由服务端冻结，输出保留父子阅读顺序。 */
export function flattenKnowledgePoints(nodes: KnowledgePoint[]): KnowledgePoint[] {
  return nodes.flatMap(node => [node, ...flattenKnowledgePoints(node.children)]);
}

/** 统一清理提交前的自由文本，避免空白答案或空选项绕过浏览器提示后触发难定位的 422。 */
export function normalizeQuestionVersion(version: QuestionVersionInput): QuestionVersionInput {
  const normalize = <T extends QuestionVersionInput | QuestionChildInput>(value: T): T => ({
    ...value,
    stem: value.stem.trim(),
    options: value.options.map(option => ({ ...option, content: option.content.trim() })),
    answers: value.answers.map(answer => answer.trim()).filter(Boolean),
    analysis: value.analysis.trim(),
    knowledgePointIds: [...new Set(value.knowledgePointIds)],
    media: value.media.map((item, index) => ({ ...item, sequence: index + 1 }))
  });
  const root = normalize(version);
  return root.type === "COMPOSITE"
    ? { ...root, children: (root.children ?? []).map(child => normalize(child)) }
    : { ...root, children: undefined };
}
