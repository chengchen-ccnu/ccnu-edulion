import type { CourseMediaAccessGrant, CourseResourceAction } from "@edu/api-client";

export interface ResourceActionController {
  run(provider: (signal: AbortSignal) => Promise<CourseMediaAccessGrant>, action: CourseResourceAction,
    fileName: string): Promise<void>;
  dispose(): void;
}

/**
 * 创建一次一授权的资料动作控制器；输入为课程域授权函数，结果只在内存 Blob URL 中打开或下载。
 * 控制器不缓存签名 URL，不自动刷新；页面离开时中止请求并回收全部临时 URL 与定时器。
 */
export function createResourceActionController(): ResourceActionController {
  const requests = new Set<AbortController>();
  const objectUrls = new Set<string>();
  const timers = new Set<ReturnType<typeof setTimeout>>();

  return {
    async run(provider, action, fileName) {
      const controller = new AbortController();
      requests.add(controller);
      const previewWindow = action === "PREVIEW" ? window.open("about:blank", "_blank") : null;
      if (action === "PREVIEW" && !previewWindow) {
        requests.delete(controller);
        throw new Error("RESOURCE_POPUP_BLOCKED");
      }
      if (previewWindow) previewWindow.opener = null;
      try {
        const grant = await provider(controller.signal);
        const response = await fetch(grant.url, { method: grant.method, headers: grant.headers,
          signal: controller.signal, cache: "no-store" });
        if (!response.ok) throw new Error("RESOURCE_READ_FAILED");
        const objectUrl = URL.createObjectURL(await response.blob());
        objectUrls.add(objectUrl);
        if (action === "PREVIEW") {
          previewWindow!.location.replace(objectUrl);
        } else {
          const anchor = document.createElement("a");
          anchor.href = objectUrl;
          anchor.download = fileName;
          anchor.click();
        }
        const timer = setTimeout(() => {
          URL.revokeObjectURL(objectUrl);
          objectUrls.delete(objectUrl);
          timers.delete(timer);
        }, 30_000);
        timers.add(timer);
      } catch (error) {
        previewWindow?.close();
        throw error;
      } finally {
        requests.delete(controller);
      }
    },
    dispose() {
      requests.forEach(request => request.abort());
      requests.clear();
      timers.forEach(timer => clearTimeout(timer));
      timers.clear();
      objectUrls.forEach(url => URL.revokeObjectURL(url));
      objectUrls.clear();
    }
  };
}
