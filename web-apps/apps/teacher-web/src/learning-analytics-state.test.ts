import { GatewayRequestError } from "@edu/api-client";
import { describe, expect, it } from "vitest";
import { analyticsError, studentState, trendWindow } from "./learning-analytics-state";

describe("teacher analytics URL and failures", () => {
  it("restores only bounded filters, selected student and a lazy detail panel", () => {
    expect(studentState({ courseId: "course", learnerId: "learner", panel: "tasks", page: "2", detailPage: "3",
      sort: "WATCHED_SECONDS", direction: "desc", pendingOnly: "true", minProgressPercent: "25" })).toMatchObject({
      courseId: "course", learnerId: "learner", panel: "tasks", page: 2, detailPage: 3, sort: "WATCHED_SECONDS",
      direction: "desc", pendingOnly: true, minProgressPercent: 25
    });
    expect(studentState({ panel: "answers", page: "-1", detailPage: "2.5", sort: "SCORE", minProgressPercent: "101" }))
      .toMatchObject({ panel: "overview", page: 1, detailPage: 1, sort: "STUDENT_NUMBER", minProgressPercent: undefined });
    expect(trendWindow("ALL")).toBe("ALL");
    expect(trendWindow("LAST_30_DAYS")).toBe("LAST_30_DAYS");
    expect(trendWindow("invalid")).toBe("LAST_7_DAYS");
  });

  it.each([401, 403, 404, 409, 422, 429, 503])("explains HTTP %s without exposing response details", status => {
    const message = analyticsError(new GatewayRequestError(new Response(null, { status })));
    expect(message.length).toBeGreaterThan(5);
    expect(message).not.toContain("undefined");
  });
  it("explains offline state", () => expect(analyticsError(new TypeError("fetch failed"))).toContain("网络不可用"));
});
