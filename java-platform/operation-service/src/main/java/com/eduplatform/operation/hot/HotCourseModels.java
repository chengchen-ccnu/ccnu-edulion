package com.eduplatform.operation.hot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 热点专属只读投影；nullable封面/降级字段始终输出，不泄露领域正文或学生身份。 */
public final class HotCourseModels {
    private HotCourseModels() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceCard(String courseId,boolean visible,String title,String summary,String instructorDisplayName,
            String categoryId,String level,String accessType,Instant publishedAt,String coverMediaId) {}
    public record SourcePage(List<SourceCard> items,boolean hasMore) {}
    public record Learning(String courseId,long learners7d,long activeEnrollments,LocalDate windowStart,LocalDate windowEnd) {}
    public record Cover(String mediaId,String url,Instant expiresAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Card(String courseId,String title,String summary,String instructorDisplayName,String categoryId,
            String level,String accessType,Instant publishedAt,String label,long learners7d,long activeEnrollments,
            String coverMediaId,String coverStatus,String coverUrl,Instant coverExpiresAt) {
        /** 输入当前时间；仅剔除已过期/即将到期签名，不进行网络授权或领域访问。 */
        public Card at(Instant now) {
            if(!"READY".equals(coverStatus) || (coverExpiresAt!=null && now.plusSeconds(5).isBefore(coverExpiresAt))) return this;
            return new Card(courseId,title,summary,instructorDisplayName,categoryId,level,accessType,publishedAt,label,
                    learners7d,activeEnrollments,coverMediaId,"UNAVAILABLE",null,null);
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Board(String status,List<Card> items,Instant generatedAt,Instant validUntil,LocalDate windowStart,LocalDate windowEnd) {
        /** 输入降级枚举，输出明确无榜单结果；不能冒充EMPTY或复用旧排名。 */
        public static Board degraded(String status) {return new Board(status,List.of(),null,null,null,null);}
    }
}
