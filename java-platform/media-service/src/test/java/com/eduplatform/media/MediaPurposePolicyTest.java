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

/** 验证 T08A/T09 已评审用途逐项开放，同时确保后续用途仍保持关闭。 */
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
    }

    @Test
    void rejectsUnreviewedEnumPurpose() {
        assertThatThrownBy(() -> policy.validate(request(
                MediaPurpose.AI_QUESTION_IMAGE, "question.png", "image/png", 5), Set.of("TEACHER"), 10))
                .isInstanceOfSatisfying(MediaException.class,
                        error -> assertThat(error.code()).isEqualTo("MEDIA.PURPOSE_NOT_ENABLED"));
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
                "questions.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 5), Set.of("STUDENT"), 10))
                .isInstanceOfSatisfying(MediaException.class, error -> assertThat(error.code()).isEqualTo("MEDIA.PURPOSE_FORBIDDEN"));
        assertThatThrownBy(() -> policy.validate(request(MediaPurpose.QUESTION_IMAGE,
                "diagram.svg", "image/svg+xml", 5), Set.of("TEACHER"), 10)).isInstanceOf(MediaException.class);
    }

    private void assertMode(MediaPurpose purpose, String filename, String mimeType, long size, UploadMode expected) {
        assertThat(policy.validate(request(purpose, filename, mimeType, size), Set.of("TEACHER"), 10).mode())
                .isEqualTo(expected);
    }

    private MediaDtos.CreateUploadRequest request(MediaPurpose purpose, String filename, String mimeType, long size) {
        return new MediaDtos.CreateUploadRequest(UUID.randomUUID(), purpose, filename, size, mimeType,
                "0".repeat(64));
    }
}
