// @vitest-environment jsdom
import { createApp, h, nextTick } from "vue";
import { describe, expect, it } from "vitest";
import AgentSources from "./components/AgentSources.vue";

describe("紧凑授权来源", () => {
  it("只常显两条，点击展开完整列表，Escape/外部点击/离开区域关闭", async () => {
    const host = document.createElement("div"); document.body.append(host);
    const references = Array.from({ length: 5 }, (_, index) => ({ referenceId: `${index}`,
      title: `知识点${index}`, sourceType: "KNOWLEDGE_POINT" as const, accessDecision: "AUTHORIZED_BY_JAVA" as const }));
    const app = createApp({ render: () => h(AgentSources, { references }) }); app.mount(host);
    try {
      expect(host.querySelectorAll("li")).toHaveLength(2);
      const button = host.querySelector("button")!;
      expect(button.textContent).toContain("还有 3 条");
      button.click(); await nextTick();
      expect(host.querySelector('[aria-label="完整授权来源"]')?.querySelectorAll("li")).toHaveLength(5);
      button.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })); await nextTick();
      expect(host.querySelector('[aria-label="完整授权来源"]')).toBeNull();
      button.click(); await nextTick(); document.body.dispatchEvent(new Event("pointerdown", { bubbles: true })); await nextTick();
      expect(button.getAttribute("aria-expanded")).toBe("false");
      button.click(); await nextTick();
      host.querySelector<HTMLButtonElement>('[aria-label="关闭来源列表"]')!.click(); await nextTick();
      expect(button.getAttribute("aria-expanded")).toBe("false");
      expect(document.activeElement).toBe(button);
      host.querySelector(".agent-sources__more")!.dispatchEvent(new MouseEvent("mouseenter")); await nextTick();
      expect(button.getAttribute("aria-expanded")).toBe("true");
      host.querySelector("section")!.dispatchEvent(new MouseEvent("mouseleave")); await nextTick();
      expect(button.getAttribute("aria-expanded")).toBe("false");
    } finally { app.unmount(); host.remove(); }
  });
});
