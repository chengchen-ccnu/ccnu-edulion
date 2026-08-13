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
});
