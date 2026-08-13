import { describe, expect, it } from "vitest";

import { LEGACY_BATCH_LIMIT, LEGACY_TASK_LIMIT, legacyBatches, readLegacyFiles, rootFingerprint } from "./legacy-import";

describe("bounded browser legacy import", () => {
  it("uses embedded exerciseID and parses edition/grade below a selected question-bank root", async () => {
    const file = new File([JSON.stringify({ exerciseID: "question-001", exerciseType: "fillin" })], "one.json", { type: "application/json" });
    Object.defineProperty(file, "webkitRelativePath", { value: "题库/北师大版/北师大版八年级上/text/one.json" });

    const [item] = await readLegacyFiles([file], new AbortController().signal);

    expect(item.externalId).toBe("question-001");
    expect(item.relativePath).toBe("题库/北师大版/北师大版八年级上/text/one.json");
    expect(item.pathEdition).toBe("北师大版");
    expect(item.pathGrade).toBe("北师大版八年级上");
    expect(item.contentSha256).toMatch(/^[a-f0-9]{64}$/);
    expect(item.relativePath).not.toMatch(/^[A-Za-z]:|^\//);
  });

  it.each([
    ["北师大版/北师大版八年级上/text/one.json", "北师大版", "北师大版八年级上"],
    ["北师大版八年级上/text/one.json", "北师大版八年级上", "北师大版八年级上"],
    ["edition/grade/one.json", "edition", "grade"]
  ])("keeps directory metadata for selection depth %s", async (relativePath, edition, grade) => {
    const file = new File([JSON.stringify({ exerciseID: "question-002" })], "one.json", { type: "application/json" });
    Object.defineProperty(file, "webkitRelativePath", { value: relativePath });

    const [item] = await readLegacyFiles([file], new AbortController().signal);

    expect(item.pathEdition).toBe(edition);
    expect(item.pathGrade).toBe(grade);
  });

  it("falls back to the file stem when exerciseID is absent", async () => {
    const file = new File([JSON.stringify({ exerciseType: "fillin" })], "fallback-id.json", { type: "application/json" });

    const [item] = await readLegacyFiles([file], new AbortController().signal);

    expect(item.externalId).toBe("fallback-id");
    expect(item.pathEdition).toBe("UNKNOWN");
    expect(item.pathGrade).toBe("UNKNOWN");
  });

  it("rejects an embedded exerciseID that cannot satisfy the API contract without truncation", async () => {
    const file = new File([JSON.stringify({ exerciseID: "x".repeat(161) })], "one.json", { type: "application/json" });

    await expect(readLegacyFiles([file], new AbortController().signal)).rejects.toThrow("LEGACY_EXTERNAL_ID_INVALID");
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
