import { describe, expect, it } from "vitest";

import { LEGACY_BATCH_LIMIT, LEGACY_TASK_LIMIT, legacyBatches, readLegacyFiles, rootFingerprint } from "./legacy-import";

describe("bounded browser legacy import", () => {
  it("reads only explicitly selected files and keeps a normalized relative path", async () => {
    const file = new File([JSON.stringify({ type: "fillin", ques: "合成题" })], "one.json", { type: "application/json" });
    Object.defineProperty(file, "webkitRelativePath", { value: "edition/grade/one.json" });

    const [item] = await readLegacyFiles([file], new AbortController().signal);

    expect(item.relativePath).toBe("edition/grade/one.json");
    expect(item.pathEdition).toBe("edition");
    expect(item.pathGrade).toBe("grade");
    expect(item.contentSha256).toMatch(/^[a-f0-9]{64}$/);
    expect(item.relativePath).not.toMatch(/^[A-Za-z]:|^\//);
  });

  it("never creates batches over 20 and never accepts a task over 1000", () => {
    const items = Array.from({ length: LEGACY_BATCH_LIMIT + 1 }, (_value, index) => ({
      externalId: String(index), relativePath: `${index}.json`, pathEdition: "UNKNOWN", pathGrade: "UNKNOWN",
      contentSha256: "a".repeat(64), json: {}
    }));

    expect(legacyBatches(items).map(batch => batch.length)).toEqual([20, 1]);
    expect(() => legacyBatches(Array.from({ length: LEGACY_TASK_LIMIT + 1 }, () => items[0]))).toThrow("LEGACY_TASK_LIMIT");
  });

  it("derives a stable root fingerprint from a non-path label", () => {
    expect(rootFingerprint("LEGACY_BROWSER_V1", "fixture-set")).toMatch(/^[a-f0-9]{64}$/);
    expect(rootFingerprint("LEGACY_BROWSER_V1", "fixture-set")).toBe(rootFingerprint("LEGACY_BROWSER_V1", " fixture-set "));
  });
});
