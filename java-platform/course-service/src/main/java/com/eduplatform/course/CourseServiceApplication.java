package com.eduplatform.course;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.eduplatform.course.config.CourseMediaProperties;
import com.eduplatform.course.config.AgentIndexProperties;
import com.eduplatform.course.config.AgentAccessProperties;

/** 启动课程领域服务，并启用媒体引用 Outbox 的受控补投调度。 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({CourseMediaProperties.class, AgentIndexProperties.class, AgentAccessProperties.class})
public class CourseServiceApplication {
    /**
     * 启动 Spring Boot 应用。
     * @param args JVM 传入的启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CourseServiceApplication.class, args);
    }
}
