import type { QuestionVersion } from "@edu/api-client";
import { describe, expect, it } from "vitest";

import { editableQuestionVersion, emptyQuestionVersion, normalizeQuestionVersion } from "./question-form";

describe("question structured form", () => {
  it("creates a one-level structured composite without free-text child packing", () => {
    const version = emptyQuestionVersion("COMPOSITE");

    expect(version.type).toBe("COMPOSITE");
    expect(version.children).toHaveLength(1);
    expect(version.children?.[0]).toMatchObject({ type: "SINGLE_CHOICE", stem: "", options: [{ key: "A" }, { key: "B" }] });
    expect(version.children?.[0]).not.toHaveProperty("children");
  });

  it("copies only immutable-version business fields into a new write graph", () => {
    const source = versionProjection();
    const editable = editableQuestionVersion(source);

    expect(editable).not.toHaveProperty("id");
    expect(editable.children?.[0]).toMatchObject({ stem: "子题", answers: ["A"], media: [] });
    editable.children![0].stem = "本地修改";
    expect(source.children[0].stem).toBe("子题");
  });

  it("normalizes whitespace, removes blank answers and re-sequences media before submit", () => {
    const version = emptyQuestionVersion("SHORT_ANSWER");
    version.stem = "  证明题  ";
    version.answers = [" 答案 ", "   "];
    version.media = [{ mediaId: "m-1", purpose: "QUESTION_IMAGE", sequence: 9 }];

    expect(normalizeQuestionVersion(version)).toMatchObject({ stem: "证明题", answers: ["答案"],
      media: [{ mediaId: "m-1", sequence: 1 }] });
  });
});

function versionProjection(): QuestionVersion {
  return { id: "v-root", versionNumber: 2, type: "COMPOSITE", difficulty: "MEDIUM", stage: "JUNIOR", grade: "SEVEN",
    subject: "MATHEMATICS", stem: "父题", options: [], answers: ["见子题"], analysis: "解析", knowledgePoints: [], media: [],
    legacySource: null, createdAt: "2026-08-12T00:00:00Z", children: [{ questionId: "q-child", versionId: "v-child",
      versionNumber: 1, sequence: 1, type: "SINGLE_CHOICE", difficulty: "EASY", stage: "JUNIOR", grade: "SEVEN",
      subject: "MATHEMATICS", stem: "子题", options: [{ key: "A", content: "1" }, { key: "B", content: "2" }],
      answers: ["A"], analysis: "", knowledgePoints: [], media: [], createdAt: "2026-08-12T00:00:00Z" }] };
}
