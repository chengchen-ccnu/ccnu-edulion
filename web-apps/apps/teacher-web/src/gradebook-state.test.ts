import { describe, expect, it } from "vitest";

import { gradebookRequestQuery, parseGradebookQuery, toGradebookQuery } from "./gradebook-state";

describe("T11C.2 gradebook URL state", () => {
  it("normalizes invalid filters without inventing a second API shape", () => {
    const state = parseGradebookQuery({ courseId: "course-1", type: "PRACTICE", page: "0", size: "999",
      submissionStatus: "UNKNOWN", gradingStatus: "WAITING", late: "sometimes", sort: "SCORE", direction: "sideways" });
    expect(state).toMatchObject({ courseId: "course-1", page: 1, size: 20, sort: "SCORE", direction: "asc" });
    expect(state.type).toBeUndefined();
    expect(state.submissionStatus).toBeUndefined();
    expect(state.gradingStatus).toBeUndefined();
    expect(state.late).toBeUndefined();
  });

  it("round-trips all supported URL state and request fields", () => {
    const state = parseGradebookQuery({ courseId: "course-1", assessmentId: "assessment-1", type: "EXAM",
      submissionStatus: "SUBMITTED", gradingStatus: "GRADED", late: "true", keyword: "  student  ",
      page: "2", size: "50", sort: "LAST_SUBMITTED_AT", direction: "desc" });
    expect(parseGradebookQuery(toGradebookQuery(state))).toEqual(state);
    expect(gradebookRequestQuery(state)).toMatchObject({ courseId: "course-1", assessmentId: "assessment-1",
      type: "EXAM", submissionStatus: "SUBMITTED", gradingStatus: "GRADED", late: true, keyword: "student",
      page: 2, size: 50, sort: "LAST_SUBMITTED_AT", direction: "desc" });
  });
});
