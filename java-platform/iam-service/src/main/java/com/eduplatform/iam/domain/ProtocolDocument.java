package com.eduplatform.iam.domain;

import java.time.Instant;

/** 不可变协议正文投影；新版本只能新增记录，历史正文与历史同意不得覆盖。 */
public class ProtocolDocument {
    private String id;
    private String documentType;
    private String documentVersion;
    private String body;
    private Instant effectiveAt;
    private boolean current;
    private boolean requiresReconsent;
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
    public boolean isRequiresReconsent() { return requiresReconsent; }
    public void setRequiresReconsent(boolean requiresReconsent) { this.requiresReconsent = requiresReconsent; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
