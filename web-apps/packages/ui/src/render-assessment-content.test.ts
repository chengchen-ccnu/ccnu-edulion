// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick, ref } from "vue";

import SafeAssessmentContent from "./SafeAssessmentContent.vue";
import { resetAssessmentMediaSessionCache } from "./assessment-media-loader";
import { renderAssessmentContent } from "./render-assessment-content";

afterEach(() => {
  resetAssessmentMediaSessionCache();
  document.body.innerHTML = "";
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("safe assessment content", () => {
  it("renders Markdown, controlled HTML, LaTeX and fill blanks while removing executable content", () => {
    const html = renderAssessmentContent(
      "# 标题\n<span data-x='bad'>受控</span><script>alert(1)</script> $x^2$ ___", true);
    expect(html).toContain("<h1>标题</h1>");
    expect(html).toContain("katex");
    expect(html).toContain("assessment-fill-blank");
    expect(html).not.toContain("<script");
    expect(html).not.toContain("data-x");
    expect(html).not.toContain("alert(1)");
  });

  it("keeps raw image URLs inert and only restores allow-listed mediaId placeholders", () => {
    const mediaId = "00000000-0000-4000-8000-000000000701";
    const html = renderAssessmentContent(`![bad](https://evil.test/a.png) {{media:${mediaId}}}`);
    expect(html).not.toContain("evil.test");
    expect(html).not.toContain("<img");
    expect(html).toContain(`data-assessment-media-id="${mediaId}"`);
  });

  it("uses a short grant only to create a blob URL and revokes it when the component leaves", async () => {
    const mediaId = "00000000-0000-4000-8000-000000000701";
    const source = ref(`题图 {{media:${mediaId}}}`);
    const grantSignals: AbortSignal[] = [];
    const resolver = vi.fn(async (_id: string, _purpose: string, signal: AbortSignal) => {
      grantSignals.push(signal);
      return { url: "https://signed.example.test/private", headers: { "X-Read": "short" },
        expiresAt: "2026-08-28T00:01:00Z" };
    });
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new Blob(["image"]), { status: 200 })));
    const createObjectURL = vi.fn(() => "blob:t12b-question-image");
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectURL });
    const host = document.createElement("div"); document.body.append(host);
    const app = createApp({ render: () => h(SafeAssessmentContent, { source: source.value,
      media: [{ mediaId, purpose: "QUESTION_IMAGE" as const, sequence: 1 }], resolveMedia: resolver }) });

    app.mount(host);
    await nextTick();
    await vi.waitFor(() => expect(host.querySelector("img")?.getAttribute("src")).toBe("blob:t12b-question-image"));
    expect(host.innerHTML).not.toContain("signed.example.test");
    app.unmount();

    expect(grantSignals[0]?.aborted).toBe(true);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:t12b-question-image");
  });

  it("shows a classified non-transient failure and lets the user retry it manually", async () => {
    const mediaId = "00000000-0000-4000-8000-000000000702";
    const resolver = vi.fn(async () => ({ url: "https://signed.example.test/private", headers: {}, expiresAt: "2099-01-01T00:00:00Z" }));
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 404 }))
      .mockResolvedValueOnce(new Response(new Blob(["image"]), { status: 200 }));
    vi.stubGlobal("fetch", fetcher); Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:manual-retry") });
    const host = document.createElement("div"); document.body.append(host);
    const app = createApp(SafeAssessmentContent, { source: `{{media:${mediaId}}}`, mediaCacheScope: "question-1",
      media: [{ mediaId, purpose: "QUESTION_IMAGE", sequence: 1 }], resolveMedia: resolver });
    app.mount(host);
    await vi.waitFor(() => expect(host.textContent).toContain("题图暂不可用"));
    expect(fetcher).toHaveBeenCalledTimes(1); host.querySelector<HTMLButtonElement>(".assessment-inline-media__retry")!.click();
    await vi.waitFor(() => expect(host.querySelector("img")?.src).toContain("blob:manual-retry"));
    expect(fetcher).toHaveBeenCalledTimes(2); app.unmount();
  });
});
