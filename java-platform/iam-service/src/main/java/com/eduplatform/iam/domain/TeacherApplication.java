package com.eduplatform.iam.domain;

import java.time.Instant;

/** 普通账号教师申请的 MySQL 状态事实；T12D 只能写 SUBMITTED，T16 才能审核。 */
public class TeacherApplication {
    private String id;
    private String userId;
    private String qualificationMediaId;
    private String statement;
    private String status;
    private String rejectionReason;
    private long revision;
    private Instant submittedAt;
    private Instant updatedAt;
    public String getId() { return id; } public void setId(String value) { id = value; }
    public String getUserId() { return userId; } public void setUserId(String value) { userId = value; }
    public String getQualificationMediaId() { return qualificationMediaId; } public void setQualificationMediaId(String value) { qualificationMediaId = value; }
    public String getStatement() { return statement; } public void setStatement(String value) { statement = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public String getRejectionReason() { return rejectionReason; } public void setRejectionReason(String value) { rejectionReason = value; }
    public long getRevision() { return revision; } public void setRevision(long value) { revision = value; }
    public Instant getSubmittedAt() { return submittedAt; } public void setSubmittedAt(Instant value) { submittedAt = value; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant value) { updatedAt = value; }
}
