import type { AttemptResult, LearnerAssessmentQuestion } from "@edu/api-client";

type ReferenceAnswer = Pick<AttemptResult["answers"][number], "correctAnswer" | "analysis">;

/**
 * 为已公布结果分配旧 `[图片N]` 的媒体分段。输入来自同一份冻结试卷与对应答案，
 * 输出分别用于题干、解析和每个参考答案；历史导入按题干/选项、解析、各答案追加图片，
 * 各区域的编号从 1 重启。没有对应绑定时保留原占位，不拼接外部 URL 或猜测其他版本。
 * 新 `{{media:UUID}}` 由组件直接按原始 media 校验，不需要这里转换。
 */
export function resultLegacyMedia(question: Pick<LearnerAssessmentQuestion, "stem" | "options" | "media">,
    reference: ReferenceAnswer | undefined) {
  const images = question.media.filter(item => item.purpose === "QUESTION_IMAGE")
    .sort((left, right) => left.sequence - right.sequence);
  const stemCount = legacyImageCount([question.stem, ...question.options.map(option => option.content)]);
  const analysisCount = legacyImageCount([reference?.analysis ?? ""]);
  let offset = stemCount + analysisCount;
  const answers = (reference?.correctAnswer ?? []).map(answer => {
    const count = legacyImageCount([answer]);
    const slice = images.slice(offset, offset + count);
    offset += count;
    return slice;
  });
  return { stem: images.slice(0, stemCount), analysis: images.slice(stemCount, stemCount + analysisCount), answers };
}

/** 同一区域可重复引用或共享题干/选项编号，按最大正整数编号计数，不能按出现次数错移后续图片。 */
function legacyImageCount(sources: string[]): number {
  let count = 0;
  for (const source of sources) {
    for (const match of source.matchAll(/\[图片(\d+)]/g)) {
      const number = Number(match[1]);
      if (Number.isSafeInteger(number) && number > count) count = number;
    }
  }
  return count;
}
