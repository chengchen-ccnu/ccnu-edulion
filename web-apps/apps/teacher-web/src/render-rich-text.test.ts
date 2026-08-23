// @vitest-environment jsdom
import { describe, expect, it } from "vitest";

import { renderSafeQuestionHtml } from "./render-rich-text";

describe("safe question rich text", () => {
  it("renders markdown, superscript and KaTeX while removing active content and external media", () => {
    const html = renderSafeQuestionHtml("**勾股**：$a^2+b^2=c^2$ <sup>2</sup><img src=x onerror=alert(1)><script>alert(2)</script>");

    expect(html).toContain("<strong>勾股</strong>");
    expect(html).toContain("class=\"katex\"");
    expect(html).toContain("<sup>2</sup>");
    expect(html).not.toMatch(/<script|<img|onerror|alert\(/i);
  });

  it("does not trust dangerous links or iframe HTML", () => {
    const html = renderSafeQuestionHtml("[危险](javascript:alert(1))<iframe src='https://evil.test'></iframe>");

    expect(html).not.toContain("javascript:");
    expect(html).not.toContain("iframe");
  });

  it("preserves KaTeX fraction positioning without allowing user-authored styles", () => {
    const html = renderSafeQuestionHtml("分式：$\\frac{kx}{2}$ <span id='unsafe-style' style='position:fixed;color:red'>正文</span>");
    const untrustedKatexHtml = renderSafeQuestionHtml("$\\href{javascript:alert(1)}{x}$");

    expect(html).toMatch(/class="vlist" style="[^"]*height:/);
    expect(html).toContain("id=\"unsafe-style\"");
    expect(html).not.toMatch(/position\s*:\s*fixed|color\s*:\s*red/i);
    expect(untrustedKatexHtml).not.toMatch(/href="javascript:/i);
  });

  it("renders visible fixed-width blanks only when a fill-blank stem requests them", () => {
    const source = "已知 <span title='属性   空格'>x</span> =     ，另一个空为____。";
    const fillBlankHtml = renderSafeQuestionHtml(source, { markFillBlanks: true });
    const regularHtml = renderSafeQuestionHtml(source);

    expect(fillBlankHtml.match(/class="question-fill-blank"/g)).toHaveLength(2);
    expect(fillBlankHtml.match(/aria-label="填空处"/g)).toHaveLength(2);
    expect(fillBlankHtml).toContain("title=\"属性   空格\"");
    expect(regularHtml).not.toContain("question-fill-blank");
  });

  it("keeps only validated stable inline-media markers as inert placeholders", () => {
    const mediaId = "00000000-0000-4000-8000-000000000901";
    const html = renderSafeQuestionHtml(`根据题意 {{media:${mediaId}}} 得到结论 {{media:not-a-uuid}}`);

    expect(html).toContain(`data-question-media-id="${mediaId}"`);
    expect(html).toContain("图片加载中…");
    expect(html).toContain("{{media:not-a-uuid}}");
    expect(html).not.toContain("<img");
  });
});
