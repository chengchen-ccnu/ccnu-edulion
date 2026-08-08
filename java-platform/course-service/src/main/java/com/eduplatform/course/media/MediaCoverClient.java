package com.eduplatform.course.media;

/** course-service 在确认课程版本可见后申请课程封面的短期原件展示投影。 */
public interface MediaCoverClient {
    /**
     * 为一个稳定 COURSE_COVER 引用申请可直接用于浏览器 img 的短期 URL。
     *
     * @param mediaId 课程版本保存的稳定封面媒体 ID
     * @param viewerId 教师本人或公开课程读取使用的可信访问者标识
     * @param traceId 当前课程读取调用链 ID
     * @return 仅用于本次响应的短期 URL，不得写入课程数据库、事件或日志
     * @throws com.eduplatform.course.service.CourseException 媒体不存在、未就绪或授权依赖不可用时抛出
     */
    String issueCover(String mediaId, String viewerId, String traceId);
}
