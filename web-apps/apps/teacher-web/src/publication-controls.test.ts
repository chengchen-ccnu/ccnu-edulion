import { describe, expect, it } from "vitest";

import { canPublishCourse, publicationControls } from "./publication-controls";

describe("teacher publication controls", () => {
  it("offers publishing the new draft without hiding the unpublish action for a live course", () => {
    expect(publicationControls({ publicationStatus: "PUBLISHED", hasUnpublishedChanges: true })).toEqual({
      showPublish: true,
      publishLabel: "发布新版本",
      showUnpublish: true
    });
  });

  it("only offers unpublishing when the live version has no draft changes", () => {
    expect(publicationControls({ publicationStatus: "PUBLISHED", hasUnpublishedChanges: false })).toEqual({
      showPublish: false,
      publishLabel: "发布新版本",
      showUnpublish: true
    });
  });

  it("offers republishing an unchanged unpublished version", () => {
    expect(publicationControls({ publicationStatus: "UNPUBLISHED", hasUnpublishedChanges: false })).toEqual({
      showPublish: true,
      publishLabel: "重新发布",
      showUnpublish: false
    });
  });

  it("offers publishing the current version for a draft or changed unpublished course", () => {
    expect(publicationControls({ publicationStatus: "DRAFT", hasUnpublishedChanges: true }).publishLabel)
      .toBe("发布当前版本");
    expect(publicationControls({ publicationStatus: "UNPUBLISHED", hasUnpublishedChanges: true }).publishLabel)
      .toBe("发布当前版本");
  });

  it("allows an unchanged unpublished version to be republished", () => {
    expect(canPublishCourse({ publicationStatus: "UNPUBLISHED", hasUnpublishedChanges: false, chapters: [] }))
      .toBe(true);
  });

  it("requires every chapter in a changed draft to contain a lesson", () => {
    expect(canPublishCourse({ publicationStatus: "PUBLISHED", hasUnpublishedChanges: true,
      chapters: [{ lessons: [] }] })).toBe(false);
    expect(canPublishCourse({ publicationStatus: "PUBLISHED", hasUnpublishedChanges: true,
      chapters: [{ lessons: [{}] }] })).toBe(true);
  });
});
