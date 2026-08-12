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
    const routeBlock = source.slice(source.indexOf("/teacher/questions"), source.indexOf("/forbidden"));
    expect(routeBlock.match(/requiredPermission: "teaching:access"/g)).toHaveLength(6);
    expect(routeBlock).not.toContain("PlaceholderPage");
  });
});
