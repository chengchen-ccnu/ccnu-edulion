import DOMPurify from "dompurify";
import katex from "katex";
import { marked } from "marked";

const mediaImageToken = /!\[([^\]\n]+)]\(media:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\)/g;
const mathToken = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\$([^\n$]+?)\$|\\\(([^\n]+?)\\\)/g;

/**
 * 将 ARTICLE 的受控 Markdown 转为可注入页面的安全 HTML。
 * 输入允许 Markdown、GFM 表格/代码、LaTeX、HTTPS 链接与 media:UUID 图片；输出删除脚本、外链图片和非 HTTPS 链接。
 */
export function renderArticleMarkdown(source: string): string {
  const images: Array<{ alt: string; mediaId: string }> = [];
  const imageTokenized = source.replace(mediaImageToken, (_match, alt: string, mediaId: string) => {
    const index = images.push({ alt, mediaId: mediaId.toLowerCase() }) - 1;
    return `@@EDU_ARTICLE_IMAGE_${index}@@`;
  });
  const formulas: string[] = [];
  const mathTokenized = imageTokenized.replace(mathToken,
    (_match, blockDollar, blockBracket, inlineDollar, inlineBracket) => {
      const formula = String(blockDollar ?? blockBracket ?? inlineDollar ?? inlineBracket ?? "");
      const index = formulas.push(katex.renderToString(formula.trim(), {
        displayMode: Boolean(blockDollar ?? blockBracket), throwOnError: false, strict: "warn",
        trust: false, output: "htmlAndMathml"
      })) - 1;
      return `@@EDU_ARTICLE_MATH_${index}@@`;
    });
  const sanitized = DOMPurify.sanitize(marked.parse(mathTokenized, {
    async: false, breaks: true, gfm: true
  }) as string, {
    FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "form", "input", "button", "img", "audio", "video", "source"],
    FORBID_ATTR: ["style", "src", "srcset", "onerror", "onclick", "onload"], ALLOW_DATA_ATTR: false
  });
  const restored = sanitized
    .replace(/@@EDU_ARTICLE_MATH_(\d+)@@/g, (_match, index: string) => formulas[Number(index)] ?? "")
    .replace(/@@EDU_ARTICLE_IMAGE_(\d+)@@/g, (_match, index: string) => {
      const image = images[Number(index)];
      return image ? `<span class="article-inline-image" data-article-media-id="${image.mediaId}" data-article-alt="${escapeAttribute(image.alt)}">图片加载中…</span>` : "";
    });
  return constrainLinks(restored);
}

/** 只保留绝对 HTTPS 链接；移除的链接仍保留可读文字，避免预览静默丢失正文。 */
function constrainLinks(html: string): string {
  const template = document.createElement("template");
  template.innerHTML = html;
  template.content.querySelectorAll("a").forEach(anchor => {
    const href = anchor.getAttribute("href") ?? "";
    let allowed = false;
    try { allowed = new URL(href).protocol === "https:"; } catch { allowed = false; }
    if (allowed) {
      anchor.setAttribute("target", "_blank");
      anchor.setAttribute("rel", "noopener noreferrer");
      return;
    }
    anchor.replaceWith(document.createTextNode(anchor.textContent ?? ""));
  });
  return template.innerHTML;
}

function escapeAttribute(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll('"', "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}
