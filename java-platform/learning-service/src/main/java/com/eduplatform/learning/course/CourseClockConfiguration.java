package com.eduplatform.learning.course;

import com.eduplatform.learning.service.QuestionException;
import java.lang.reflect.Method;
import java.util.List;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.beans.factory.config.BeanDefinition;
import com.eduplatform.learning.web.LearningDtos;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** 显式课程时钟标记的事务适配；仅拦截已标注的 Learning 方法，旧 ID 先校验本域主体，课程 owner 由权威 HTTP 事实校验。 */
@Configuration
public class CourseClockConfiguration {
    /** 外层课程事务先于旧 assessment 事务；旧方法的 readOnly 注解不能让恢复补时落在只读连接上。 */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public DefaultPointcutAdvisor courseClockAdvisor(ObjectProvider<CourseTaskClock> clock, ObjectProvider<JdbcTemplate> jdbc) {
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return method.isAnnotationPresent(CourseClock.class);
            }
        };
        MethodInterceptor interceptor = invocation -> {
            CourseClock marker = invocation.getMethod().getAnnotation(CourseClock.class);
            Object[] args = invocation.getArguments();
            Object resource = args[marker.id()];
            String id;
            if (marker.value() == CourseClock.Kind.REQUEST) {
                var requestCourse = ((LearningDtos.CreateAssessmentRequest) resource).courseId();
                id = requestCourse == null ? null : requestCourse.toString();
            } else {
                id = resource == null ? null : resource.toString();
            }
            String actor = marker.actor() == CourseClock.Actor.NONE ? null : args[marker.subject()].toString();
            String courseId = resolve(jdbc.getObject(), marker, id, actor);
            Object result = clock.getObject().within(courseId, marker.actor() == CourseClock.Actor.TEACHER ? actor : null,
                    marker.background(), () -> {
                        try { return invocation.proceed(); }
                        catch (RuntimeException | Error failure) { throw failure; }
                        catch (Throwable failure) { throw new IllegalStateException("learning domain invocation failed", failure); }
                    });
            return result == null && invocation.getMethod().getReturnType() == int.class ? 0 : result;
        };
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return advisor;
    }

    /** 旧 ID 在 Learning 本域反查并约束 owner；越权和未知统一 404，不能先泄露暂停课程存在。 */
    private static String resolve(JdbcTemplate jdbc, CourseClock marker, String id, String actor) {
        if (id == null || (marker.value() == CourseClock.Kind.COURSE || marker.value() == CourseClock.Kind.REQUEST)) return id;
        String sql = switch (marker.value()) {
            case ASSESSMENT -> "SELECT a.course_id FROM assessment a WHERE a.id=?";
            case ATTEMPT -> "SELECT a.course_id FROM assessment_attempt t JOIN assessment a ON a.id=t.assessment_id WHERE t.id=?";
            case NOTE -> "SELECT course_id FROM learning_note WHERE id=?";
            case WRONG -> "SELECT course_id FROM wrong_question WHERE id=?";
            default -> throw new IllegalStateException("unsupported clock resource");
        };
        List<String> rows;
        if (marker.actor() == CourseClock.Actor.TEACHER) {
            rows = jdbc.query(sql + " AND a.owner_teacher_id=?", (rs, n) -> rs.getString(1), id, actor);
        } else if (marker.actor() == CourseClock.Actor.LEARNER && marker.value() != CourseClock.Kind.ASSESSMENT) {
            rows = jdbc.query(sql + (marker.value() == CourseClock.Kind.ATTEMPT ? " AND t.learner_id=?" : " AND learner_id=?"),
                    (rs, n) -> rs.getString(1), id, actor);
        } else {
            rows = jdbc.query(sql, (rs, n) -> rs.getString(1), id);
        }
        if (rows.isEmpty()) {
            // 后台读到任务已清理时沿用旧空操作；前台始终返回相同安全 404。
            if (marker.background()) return null;
            throw new QuestionException(HttpStatus.NOT_FOUND, "LEARNING.NOT_FOUND", "学习资源不存在或不可见");
        }
        return rows.get(0);
    }
}
