// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, h, nextTick } from "vue";

import SafeArticleContent from "./SafeArticleContent.vue";
import { renderArticleMarkdown } from "./render-article-markdown";

afterEach(() => { document.body.innerHTML = ""; vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("safe article markdown", () => {
  it("renders GFM, code and LaTeX while allowing only HTTPS links and media placeholders", () => {
    const mediaId = "00000000-0000-4000-8000-000000000701";
    const html = renderArticleMarkdown(`# 标题\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n\`code\` $x^2$\n\n`+
      `[安全](https://example.test/a) [不安全](http://example.test) ![图](media:${mediaId}) `+
      `![外链](https://evil.test/a.png)<script>alert(1)</script>`);
    expect(html).toContain("<table>");
    expect(html).toContain("<code>code</code>");
    expect(html).toContain("katex");
    expect(html).toContain('href="https://example.test/a"');
    expect(html).not.toContain("http://example.test");
    expect(html).not.toContain("evil.test");
    expect(html).not.toContain("<script");
    expect(html).toContain(`data-article-media-id="${mediaId}"`);
  });

  it("emits rendered only after an allow-listed image becomes a Blob URL and cleans it on leave", async () => {
    const mediaId = "00000000-0000-4000-8000-000000000701";
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new Blob(["image"]), { status: 200 })));
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:t12f2-article") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectURL });
    vi.spyOn(HTMLImageElement.prototype, "src", "set").mockImplementation(function (this: HTMLImageElement, value: string) {
      this.setAttribute("src", value);
      queueMicrotask(() => this.onload?.(new Event("load")));
    });
    const rendered = vi.fn();
    const host = document.createElement("div"); document.body.append(host);
    const app = createApp({ render: () => h(SafeArticleContent, {
      source: `![替代文字](media:${mediaId})`, images: [{ resourceId: "resource-1", mediaId,
        grant: { mediaId, mimeType: "image/png", sizeBytes: 5, method: "GET", url: "https://signed.example.test/image", headers: {}, expiresAt: "2099-01-01T00:00:00Z" } }],
      onRendered: rendered
    }) });

    app.mount(host); await nextTick();
    await vi.waitFor(() => expect(host.querySelector("img")?.src).toContain("blob:t12f2-article"));
    expect(rendered).toHaveBeenCalledOnce();
    expect(host.innerHTML).not.toContain("signed.example.test");
    app.unmount();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:t12f2-article");
  });

  it("does not report success when a protected image Blob cannot be decoded", async () => {
    const mediaId = "00000000-0000-4000-8000-000000000703";
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new Blob(["broken"]), { status: 200 })));
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:t12f2-broken") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
    vi.spyOn(HTMLImageElement.prototype, "src", "set").mockImplementation(function (this: HTMLImageElement, value: string) {
      this.setAttribute("src", value);
      queueMicrotask(() => this.onerror?.(new Event("error")));
    });
    const rendered = vi.fn(); const failed = vi.fn();
    const host = document.createElement("div"); document.body.append(host);
    const app = createApp({ render: () => h(SafeArticleContent, {
      source: `![损坏图片](media:${mediaId})`, images: [{ mediaId, grant: {
        mediaId, mimeType: "image/png", sizeBytes: 6, method: "GET", url: "https://signed.example.test/broken", headers: {}, expiresAt: "2099-01-01T00:00:00Z"
      } }], onRendered: rendered, onRenderError: failed
    }) });
    app.mount(host);
    await vi.waitFor(() => expect(failed).toHaveBeenCalledWith("正文图片解码失败，未记录完成状态。"));
    expect(rendered).not.toHaveBeenCalled();
    app.unmount();
  });

  it("does not report success when a Markdown image has no matching protected projection", async () => {
    const rendered = vi.fn(); const failed = vi.fn();
    const host = document.createElement("div"); document.body.append(host);
    const app = createApp({ render: () => h(SafeArticleContent, { source:
      "![缺失](media:00000000-0000-4000-8000-000000000702)", images: [], onRendered: rendered, onRenderError: failed }) });
    app.mount(host);
    await vi.waitFor(() => expect(failed).toHaveBeenCalledOnce());
    expect(rendered).not.toHaveBeenCalled();
    app.unmount();
  });

  it("requests a fresh business projection when a short-lived image grant expires", async () => {
    const mediaId = "00000000-0000-4000-8000-000000000704";
    const freshGrant = { mediaId, mimeType: "image/png", sizeBytes: 5, method: "GET" as const, url: "https://signed.example.test/fresh-image",
      headers: {}, expiresAt: "2099-01-01T00:00:00Z" };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
      .mockResolvedValueOnce(new Response(new Blob(["image"]), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:t12f2-refresh") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
    vi.spyOn(HTMLImageElement.prototype, "src", "set").mockImplementation(function (this: HTMLImageElement, value: string) {
      this.setAttribute("src", value);
      queueMicrotask(() => this.onload?.(new Event("load")));
    });
    const resolver = vi.fn(async (_id: string, _signal: AbortSignal, refresh?: boolean) => {
      expect(refresh).toBe(true);
      return freshGrant;
    });
    const rendered = vi.fn();
    const host = document.createElement("div"); document.body.append(host);
    const app = createApp({ render: () => h(SafeArticleContent, {
      source: `![替代文字](media:${mediaId})`, images: [{ mediaId, grant: {
        mediaId, mimeType: "image/png", sizeBytes: 5, method: "GET", url: "https://signed.example.test/expired-image", headers: {}, expiresAt: "2020-01-01T00:00:00Z"
      } }], resolveImage: resolver, onRendered: rendered
    }) });

    app.mount(host); await nextTick();
    await vi.waitFor(() => expect(host.querySelector("img")?.src).toContain("blob:t12f2-refresh"));
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0][0]).toBe("https://signed.example.test/expired-image");
    expect(fetchMock.mock.calls[1][0]).toBe("https://signed.example.test/fresh-image");
    expect(resolver).toHaveBeenCalledOnce();
    expect(rendered).toHaveBeenCalledOnce();
    app.unmount();
  });
});
