package com.enterprise.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class ReachAiKnowledgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReachAiKnowledgeServiceApplication.class, args);
    }
}
