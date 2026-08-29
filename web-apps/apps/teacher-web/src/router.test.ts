import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

describe("T10 teacher question routes", () => {
  const source = readFileSync(fileURLToPath(new URL("./router.ts", import.meta.url)), "utf8");

  it.each([
    "/teacher/questions", "/teacher/questions/new", "/teacher/questions/:questionId",
    "/teacher/questions/:questionId/edit", "/teacher/question-imports", "/teacher/question-imports/:importTaskId"
  ])("registers the frozen route %s", path => {
    expect(source).toContain(`path: "${path}"`);
  });

  it("guards every T10 route with teaching:access instead of a placeholder page", () => {
    const routeBlock = source.slice(source.indexOf("/teacher/questions"), source.indexOf("/teacher/assessments"));
    expect(routeBlock.match(/requiredPermission: "teaching:access"/g)).toHaveLength(6);
    expect(routeBlock).not.toContain("PlaceholderPage");
  });
});

describe("T12B teacher assessment route", () => {
  const source = readFileSync(fileURLToPath(new URL("./router.ts", import.meta.url)), "utf8");

  it("registers exactly the frozen assessment route and protects it by teaching capability", () => {
    const matches = source.match(/path: "\/teacher\/assessments"/g) ?? [];
    expect(matches).toHaveLength(1);
    const block = source.slice(source.indexOf('/teacher/assessments"'), source.indexOf('/forbidden"'));
    expect(block).toContain('requiredPermission: "teaching:access"');
    expect(block).not.toContain("PlaceholderPage");
  });
});
