import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const curriculum = readFileSync(new URL("./views/TeacherCourseCurriculumPage.vue", import.meta.url), "utf8");
const editor = readFileSync(new URL("./components/ArticleMarkdownEditor.vue", import.meta.url), "utf8");
const uploader = readFileSync(new URL("./components/MediaUploadField.vue", import.meta.url), "utf8");

describe("T12F.2 teacher content boundaries", () => {
  it("keeps article editing in the existing curriculum page with one shared safe renderer", () => {
    expect(curriculum).toContain("ArticleMarkdownEditor");
    expect(editor).toContain("SafeArticleContent");
    for (const tool of ["标题", "列表", "表格", "LaTeX", "代码", "链接", "正文图片"]) expect(editor).toContain(tool);
    expect(uploader).toContain('"ARTICLE_IMAGE"');
    expect(uploader).toContain('next.stage === "READY"');
    expect(curriculum).toContain("不提交 Bucket、Object Key 或签名 URL");
  });

  it("confirms type conversion before mutating and protects dirty drafts on navigation", () => {
    expect(curriculum).toContain("if (!window.confirm(`确认将课时从");
    expect(curriculum).toContain("select.value = lesson.type; return;");
    expect(curriculum).toContain("onBeforeRouteLeave");
    expect(curriculum).toContain("beforeunload");
    expect(curriculum).toContain("expectedRevision: course.value.revision");
    expect(curriculum).toContain("你的输入仍保留");
  });

  it("never exposes a subtitle surface and keeps article images out of independent resources", () => {
    expect(curriculum).not.toMatch(/purpose="SUBTITLE"/);
    expect(curriculum).not.toMatch(/字幕按钮|字幕菜单|上传字幕/);
    expect(curriculum).toContain('item.purpose === "COURSEWARE" || item.purpose === "ARTICLE_ATTACHMENT"');
    expect(curriculum).toContain("references.has(resource.mediaId.toLowerCase())");
  });
});
