package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.UploadMode;
import com.eduplatform.media.service.MediaException;
import com.eduplatform.media.service.MediaPurposePolicy;
import com.eduplatform.media.web.MediaDtos;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证已评审用途逐项开放，并覆盖 AI 题图按学习能力授权的私有上传边界。 */
class MediaPurposePolicyTest {
    private final MediaPurposePolicy policy = new MediaPurposePolicy();

    @Test
    void enablesReviewedCoursePurposesWithTheirDeclaredFilePolicies() {
        assertMode(MediaPurpose.VIDEO_POSTER, "poster.webp", "image/webp", 5, UploadMode.SINGLE_PUT);
        assertMode(MediaPurpose.SUBTITLE, "lesson.vtt", "text/vtt", 5, UploadMode.SINGLE_PUT);
        assertMode(MediaPurpose.COURSEWARE, "slides.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", 20,
                UploadMode.MULTIPART);
        assertMode(MediaPurpose.ARTICLE_ATTACHMENT, "reading.pdf", "application/pdf", 20,
                UploadMode.MULTIPART);
        assertMode(MediaPurpose.ARTICLE_IMAGE, "formula.webp", "image/webp", 10L * 1024 * 1024,
                UploadMode.SINGLE_PUT);
    }

    @Test
    /** 正文图片固定单对象直传，并严格拒绝超限、GIF、SVG 与伪装 MIME。 */
    void articleImageUsesStrictRasterPolicy() {
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.ARTICLE_IMAGE,
                "animated.gif", "image/gif", 1024), Set.of("TEACHER"), Set.of(), 10))
                .isInstanceOf(MediaException.class);
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.ARTICLE_IMAGE,
                "vector.svg", "image/svg+xml", 1024), Set.of("TEACHER"), Set.of(), 10))
                .isInstanceOf(MediaException.class);
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.ARTICLE_IMAGE,
                "oversized.png", "image/png", 10L * 1024 * 1024 + 1), Set.of("TEACHER"), Set.of(), 10))
                .isInstanceOfSatisfying(MediaException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMON.VALIDATION_FAILED"));
    }

    @Test
    void aiQuestionImageRequiresLearningCapabilityAndStrictRasterPolicy() {
        assertThat(policy.validate(request(MediaPurpose.AI_QUESTION_IMAGE, "question.webp", "image/webp", 5),
                Set.of("TEACHER"), Set.of("learning:access"), 10).mode()).isEqualTo(UploadMode.SINGLE_PUT);
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.AI_QUESTION_IMAGE,
                "question.png", "image/png", 5), Set.of("STUDENT"), Set.of(), 10))
                .isInstanceOfSatisfying(MediaException.class,
                        error -> assertThat(error.code()).isEqualTo("MEDIA.PURPOSE_FORBIDDEN"));
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.AI_QUESTION_IMAGE,
                "question.gif", "image/gif", 5), Set.of("STUDENT"), Set.of("learning:access"), 10))
                .isInstanceOf(MediaException.class);
    }

    @Test
    void enablesReviewedQuestionPurposesAndSelectsMultipartOnlyForLargeEligibleFiles() {
        assertMode(MediaPurpose.QUESTION_IMAGE, "diagram.png", "image/png", 20, UploadMode.SINGLE_PUT);
        assertMode(MediaPurpose.QUESTION_AUDIO, "prompt.mp3", "audio/mpeg", 20, UploadMode.MULTIPART);
        assertMode(MediaPurpose.QUESTION_ATTACHMENT, "proof.pdf", "application/pdf", 20, UploadMode.MULTIPART);
        assertMode(MediaPurpose.QUESTION_IMPORT_SOURCE, "questions.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 20, UploadMode.MULTIPART);
    }

    @Test
    void questionPurposeRejectsWrongRoleMimeAndExtension() {
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.QUESTION_IMPORT_SOURCE,
                "questions.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 5), Set.of("STUDENT"), Set.of(), 10))
                .isInstanceOfSatisfying(MediaException.class, error -> assertThat(error.code()).isEqualTo("MEDIA.PURPOSE_FORBIDDEN"));
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.QUESTION_IMAGE,
                "diagram.svg", "image/svg+xml", 5), Set.of("TEACHER"), Set.of(), 10)).isInstanceOf(MediaException.class);
    }

    @Test
    /** 普通学习账号可上传严格私有资质；教师角色不能借此重复申请或上传第二份身份材料。 */
    void enablesTeacherQualificationOnlyForStudentRole() {
        assertThat(policy.validate(request(MediaPurpose.TEACHER_QUALIFICATION,
                "qualification.pdf", "application/pdf", 20), Set.of("STUDENT"), Set.of(), 10).mode())
                .isEqualTo(UploadMode.MULTIPART);
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.TEACHER_QUALIFICATION,
                "qualification.pdf", "application/pdf", 5), Set.of("TEACHER"), Set.of(), 10))
                .isInstanceOfSatisfying(MediaException.class,
                        error -> assertThat(error.code()).isEqualTo("MEDIA.PURPOSE_FORBIDDEN"));
    }

    private void assertMode(MediaPurpose purpose, String filename, String mimeType, long size, UploadMode expected) {
        assertThat(policy.validate(request(purpose, filename, mimeType, size), Set.of("TEACHER"), Set.of(), 10).mode())
                .isEqualTo(expected);
    }

    private MediaDtos.CreateUploadRequest request(MediaPurpose purpose, String filename, String mimeType, long size) {
        return new MediaDtos.CreateUploadRequest(UUID.randomUUID(), purpose, filename, size, mimeType,
                "0".repeat(64));
    }
}
