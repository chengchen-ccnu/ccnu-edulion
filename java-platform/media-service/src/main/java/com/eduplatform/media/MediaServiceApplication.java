package com.eduplatform.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.eduplatform.media.config.MediaProperties;

/**
 * 通用媒体服务入口，组装上传、对象存储、可靠事件和异步处理能力。
 *
 * <p>本服务只保存媒体资产与引用台账，不保存课程、题目或学习领域实体。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MediaProperties.class)
public class MediaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
