import DOMPurify from "dompurify";
import katex from "katex";
import { marked } from "marked";

const mathTokens = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\$([^\n$]+?)\$|\\\(([^\n]+?)\\\)/g;
const inlineMediaTokens = /\{\{media:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})}}/g;
// HTML 分支先消费完整标签，避免把属性排版空格误认成题目空位；其余分支覆盖历史题库的常见空位写法。
const fillBlankTokens = /<!--[\s\S]*?-->|<\/?[A-Za-z][^>]*>| {3,}|_{2,}|＿{2,}|\u00a0{2,}|　{2,}/g;

type RenderQuestionOptions = { markFillBlanks?: boolean };

/**
 * 将教师题目中的 Markdown/LaTeX 转为经过白名单清洗的 HTML。
 * 输入可以含普通 Markdown、上标 HTML 与行内/块级公式；输出禁止脚本、事件属性、外链媒体和嵌入框架，
 * 页面只能渲染此函数的结果，不能把服务端原文直接交给 v-html。
 */
export function renderSafeQuestionHtml(source: string, options: RenderQuestionOptions = {}): string {
  const mediaIds: string[] = [];
  const withMediaTokens = source.replace(inlineMediaTokens, (_match, mediaId: string) => {
    const index = mediaIds.push(mediaId.toLowerCase()) - 1;
    return `@@EDU_MEDIA_${index}@@`;
  });
  const formulas: string[] = [];
  const withTokens = withMediaTokens.replace(mathTokens, (match, blockDollar, blockBracket, inlineDollar, inlineBracket) => {
    const formula = blockDollar ?? blockBracket ?? inlineDollar ?? inlineBracket ?? "";
    const displayMode = Boolean(blockDollar ?? blockBracket);
    const rendered = katex.renderToString(formula.trim(), {
      displayMode,
      throwOnError: false,
      strict: "warn",
      trust: false,
      output: "htmlAndMathml"
    });
    const index = formulas.push(rendered) - 1;
    return `@@EDU_MATH_${index}@@`;
  });
  let blankCount = 0;
  const withBlankTokens = options.markFillBlanks ? withTokens.replace(fillBlankTokens, match => {
    if (match.startsWith("<")) return match;
    return `@@EDU_FILL_BLANK_${blankCount++}@@`;
  }) : withTokens;
  const parsed = marked.parse(withBlankTokens, { async: false, breaks: true, gfm: true }) as string;
  const sanitized = DOMPurify.sanitize(parsed, {
    FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "form", "input", "button", "img", "audio", "video", "source"],
    FORBID_ATTR: ["style", "src", "srcset", "onerror", "onclick", "onload"],
    ALLOW_DATA_ATTR: false
  });

  // 必须先清洗用户可控的 Markdown/HTML，再恢复由本地代码生成的可信片段。
  // KaTeX 的分式、根式等布局依赖公式特有的内联 top/height 样式；trust=false 会禁止公式调用危险 HTML 命令，
  // 因此这里只保留 KaTeX 自己生成的定位样式，不会放开题目原文中的任意 style 或事件属性。
  return sanitized
    .replace(/@@EDU_MATH_(\d+)@@/g, (_match, index: string) => formulas[Number(index)] ?? "")
    .replace(/@@EDU_FILL_BLANK_(\d+)@@/g,
      '<span class="question-fill-blank" aria-label="填空处"></span>')
    .replace(/@@EDU_MEDIA_(\d+)@@/g, (_match, index: string) => {
      const mediaId = mediaIds[Number(index)];
      return mediaId
        ? `<span class="question-inline-media" data-question-media-id="${mediaId}">图片加载中…</span>` : "";
    });
}
