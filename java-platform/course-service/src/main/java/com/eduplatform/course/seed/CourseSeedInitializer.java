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
/**
 * 仅在 local/test 显式开启时写入固定公开课程验收数据。
 *
 * <p>各语句使用固定 ID 和 upsert，重复启动不会无限追加；事务失败会整体回滚。生产环境不会
 * 装配本初始化器，清理由配套 cleanup-course-seed.sql 完成。</p>
 */
public class CourseSeedInitializer implements ApplicationRunner {
    private final CourseSeedMapper mapper;

    public CourseSeedInitializer(CourseSeedMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    /** 按外键依赖顺序幂等写入分类、课程、版本、章节和课时，最后切换到发布状态。 */
    public void run(ApplicationArguments args) {
        mapper.upsertCategory();
        mapper.upsertCourse();
        mapper.upsertVersion();
        mapper.upsertChapter();
        mapper.upsertLesson();
        mapper.publishCourse();
    }
}
