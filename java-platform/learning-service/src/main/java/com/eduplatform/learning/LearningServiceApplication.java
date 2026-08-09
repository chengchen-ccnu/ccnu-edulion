package com.eduplatform.learning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
/** 启动学习域服务；T09 仅启用题库、知识点与异步导入，不创建作答或成绩能力。 */
public class LearningServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LearningServiceApplication.class, args);
    }
}
