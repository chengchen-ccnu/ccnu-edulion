package com.eduplatform.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.course.service.CourseException;
import org.junit.jupiter.api.Test;

class TeacherIdentityTest {
    @Test
    void requiresTrustedTeacherIdentity() {
        assertThatThrownBy(() -> TeacherIdentity.require(null, null))
                .isInstanceOfSatisfying(CourseException.class,
                        error -> assertThat(error.status().value()).isEqualTo(401));
        assertThatThrownBy(() -> TeacherIdentity.require("00000000-0000-4000-8000-000000000001", "STUDENT"))
                .isInstanceOfSatisfying(CourseException.class,
                        error -> assertThat(error.status().value()).isEqualTo(403));

        assertThat(TeacherIdentity.require(
                "00000000-0000-4000-8000-000000000002", "STUDENT,TEACHER").userId())
                .isEqualTo("00000000-0000-4000-8000-000000000002");
    }
}
