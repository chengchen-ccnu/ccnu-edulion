package com.eduplatform.course.domain;

/**
 * 课程版本内一个课时对媒体资产的稳定引用。
 *
 * <p>只保存 mediaId、用途与顺序；二进制对象、处理状态和短期 URL 始终属于 media-service。</p>
 */
public class LessonMediaResourceEntity {
    private String id;
    private String lessonId;
    private String mediaId;
    private String purpose;
    private int position;
    private String displayName;
    private String presentation;
    private String originalFileName;
    private String mimeType;
    private long sizeBytes;
    private String variantNames;
    private String processingContractVersion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLessonId() { return lessonId; }
    public void setLessonId(String lessonId) { this.lessonId = lessonId; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPresentation() { return presentation; }
    public void setPresentation(String presentation) { this.presentation = presentation; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getVariantNames() { return variantNames; }
    public void setVariantNames(String variantNames) { this.variantNames = variantNames; }
    public String getProcessingContractVersion() { return processingContractVersion; }
    public void setProcessingContractVersion(String processingContractVersion) { this.processingContractVersion = processingContractVersion; }
}
