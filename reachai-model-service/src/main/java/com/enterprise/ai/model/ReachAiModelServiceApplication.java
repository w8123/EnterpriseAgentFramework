package com.enterprise.ai.model;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.enterprise.ai.model")
@EnableScheduling
@SpringBootApplication
public class ReachAiModelServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReachAiModelServiceApplication.class, args);
    }
}
