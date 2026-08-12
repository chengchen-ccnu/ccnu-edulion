import DOMPurify from "dompurify";
import katex from "katex";
import { marked } from "marked";

const mathTokens = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\$([^\n$]+?)\$|\\\(([^\n]+?)\\\)/g;

/**
 * 将教师题目中的 Markdown/LaTeX 转为经过白名单清洗的 HTML。
 * 输入可以含普通 Markdown、上标 HTML 与行内/块级公式；输出禁止脚本、事件属性、外链媒体和嵌入框架，
 * 页面只能渲染此函数的结果，不能把服务端原文直接交给 v-html。
 */
export function renderSafeQuestionHtml(source: string): string {
  const formulas: string[] = [];
  const withTokens = source.replace(mathTokens, (match, blockDollar, blockBracket, inlineDollar, inlineBracket) => {
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
  const parsed = marked.parse(withTokens, { async: false, breaks: true, gfm: true }) as string;
  const restored = parsed.replace(/@@EDU_MATH_(\d+)@@/g, (_match, index: string) => formulas[Number(index)] ?? "");
  return DOMPurify.sanitize(restored, {
    FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "form", "input", "button", "img", "audio", "video", "source"],
    FORBID_ATTR: ["style", "src", "srcset", "onerror", "onclick", "onload"],
    ALLOW_DATA_ATTR: false
  });
}
