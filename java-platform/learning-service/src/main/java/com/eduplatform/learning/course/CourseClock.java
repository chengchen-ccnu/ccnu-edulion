package com.eduplatform.learning.course;

import java.lang.annotation.*;

/** 明确标记领域方法的课程来源及主体；拦截器在原事务之前锁课程，旧 attempt ID 也不能跳过授权。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CourseClock {
    Kind value() default Kind.COURSE;
    int id() default 1;
    Actor actor() default Actor.LEARNER;
    int subject() default 0;
    boolean background() default false;
    enum Kind { COURSE, REQUEST, ASSESSMENT, ATTEMPT, NOTE, WRONG }
    enum Actor { LEARNER, TEACHER, NONE }
}
