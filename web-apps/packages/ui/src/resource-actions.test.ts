// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";

import { createResourceActionController } from "./resource-actions";

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("course resource actions", () => {
  it("requests a new grant per action and revokes temporary URLs on dispose", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new Blob(["file"]), { status: 200 })));
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true,
      value: vi.fn().mockReturnValueOnce("blob:preview").mockReturnValueOnce("blob:download") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectURL });
    const replace = vi.fn();
    vi.spyOn(window, "open").mockReturnValue({ opener: window, location: { replace }, close: vi.fn() } as unknown as Window);
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    const provider = vi.fn(async () => ({ method: "GET" as const, url: "https://signed.example.test/file",
      headers: {}, expiresAt: "2099-01-01T00:00:00Z" }));
    const controller = createResourceActionController();

    await controller.run(provider, "PREVIEW", "file.pdf");
    await controller.run(provider, "DOWNLOAD", "file.pdf");
    expect(provider).toHaveBeenCalledTimes(2);
    expect(replace).toHaveBeenCalledWith("blob:preview");
    controller.dispose();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:preview");
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:download");
  });
});
