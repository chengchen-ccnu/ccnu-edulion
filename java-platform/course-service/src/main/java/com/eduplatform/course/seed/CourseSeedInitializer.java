package com.eduplatform.course.seed;

import com.eduplatform.course.persistence.CourseSeedMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "platform.course.seed", name = "enabled", havingValue = "true")
public class CourseSeedInitializer implements ApplicationRunner {
    private final CourseSeedMapper mapper;

    public CourseSeedInitializer(CourseSeedMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        mapper.upsertCategory();
        mapper.upsertCourse();
        mapper.upsertVersion();
        mapper.upsertChapter();
        mapper.upsertLesson();
        mapper.publishCourse();
    }
}
