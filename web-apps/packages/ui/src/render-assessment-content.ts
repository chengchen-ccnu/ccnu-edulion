import DOMPurify from "dompurify";
import katex from "katex";
import { marked } from "marked";

const mathTokens = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\$([^\n$]+?)\$|\\\(([^\n]+?)\\\)/g;
const inlineMediaTokens = /\{\{media:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})}}/g;
const fillBlankTokens = /<!--[\s\S]*?-->|<\/?[A-Za-z][^>]*>| {3,}|_{2,}|＿{2,}|\u00a0{2,}|　{2,}/g;

/**
 * 把测评快照中的 Markdown、受控 HTML 与 LaTeX 转为安全 HTML。
 * 输入只允许稳定 mediaId 占位；输出先清洗用户内容，再恢复本地 KaTeX 与媒体占位，绝不加载原文 URL。
 */
export function renderAssessmentContent(source: string, markFillBlanks = false): string {
  const mediaIds: string[] = [];
  const mediaTokenized = source.replace(inlineMediaTokens, (_match, mediaId: string) => {
    const index = mediaIds.push(mediaId.toLowerCase()) - 1;
    return `@@EDU_ASSESSMENT_MEDIA_${index}@@`;
  });
  const formulas: string[] = [];
  const mathTokenized = mediaTokenized.replace(mathTokens,
    (_match, blockDollar, blockBracket, inlineDollar, inlineBracket) => {
      const formula = String(blockDollar ?? blockBracket ?? inlineDollar ?? inlineBracket ?? "");
      const index = formulas.push(katex.renderToString(formula.trim(), {
        displayMode: Boolean(blockDollar ?? blockBracket), throwOnError: false, strict: "warn",
        trust: false, output: "htmlAndMathml"
      })) - 1;
      return `@@EDU_ASSESSMENT_MATH_${index}@@`;
    });
  let blankIndex = 0;
  const blankTokenized = markFillBlanks ? mathTokenized.replace(fillBlankTokens, match =>
    match.startsWith("<") ? match : `@@EDU_ASSESSMENT_BLANK_${blankIndex++}@@`) : mathTokenized;
  const sanitized = DOMPurify.sanitize(marked.parse(blankTokenized, {
    async: false, breaks: true, gfm: true
  }) as string, {
    FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "form", "input", "button", "img", "audio", "video", "source"],
    FORBID_ATTR: ["style", "src", "srcset", "onerror", "onclick", "onload"], ALLOW_DATA_ATTR: false
  });
  return sanitized
    .replace(/@@EDU_ASSESSMENT_MATH_(\d+)@@/g, (_match, index: string) => formulas[Number(index)] ?? "")
    .replace(/@@EDU_ASSESSMENT_BLANK_(\d+)@@/g,
      '<span class="assessment-fill-blank" aria-label="填空处"></span>')
    .replace(/@@EDU_ASSESSMENT_MEDIA_(\d+)@@/g, (_match, index: string) => {
      const mediaId = mediaIds[Number(index)];
      return mediaId
        ? `<span class="assessment-inline-media" data-assessment-media-id="${mediaId}">图片加载中…</span>` : "";
    });
}
