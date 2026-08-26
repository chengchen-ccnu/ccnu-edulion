package com.eduplatform.learning.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.learning.service.QuestionException;
import org.junit.jupiter.api.Test;

/** 验证 T11 以能力而非固定角色授权学习和教学入口。 */
class LearningIdentityTest {
    private static final String USER = "00000000-0000-4000-8000-000000018001";

    @Test
    void teacherRoleMayLearnWhenLearningCapabilityIsPresent() {
        assertThat(LearningIdentity.learner(USER, "TEACHER", "learning:access,teaching:access").userId())
                .isEqualTo(USER);
        assertThat(LearningIdentity.teaching(USER, "STAFF", "teaching:access").userId()).isEqualTo(USER);
    }

    @Test
    void missingCapabilityIs403AndInvalidIdentityIs401() {
        assertThatThrownBy(() -> LearningIdentity.learner(USER, "STUDENT", ""))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(403));
        assertThatThrownBy(() -> LearningIdentity.learner("spoofed", "STUDENT", "learning:access"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(401));
    }
}
