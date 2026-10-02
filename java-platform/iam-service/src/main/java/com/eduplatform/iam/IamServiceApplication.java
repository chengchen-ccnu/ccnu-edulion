package com.eduplatform.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IamServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IamServiceApplication.class, args);
    }
}
