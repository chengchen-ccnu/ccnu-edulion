import { describe, expect, it } from "vitest";

import { apiUrl } from "./index";

describe("apiUrl", () => {
  it("targets the OpenAPI mock gateway", () => {
    expect(apiUrl("api/v1/courses")).toBe("http://127.0.0.1:4010/api/v1/courses");
  });
});
