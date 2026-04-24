package com.enterprise.ai.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Agent 编排服务启动类
 */
@SpringBootApplication
@EnableFeignClients
@EnableAsync
@EnableScheduling
public class AiAgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentServiceApplication.class, args);
    }
}
