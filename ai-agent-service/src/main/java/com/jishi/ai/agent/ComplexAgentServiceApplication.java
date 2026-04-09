package com.jishi.ai.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI Agent 编排服务启动类
 */
@SpringBootApplication
@EnableFeignClients
public class ComplexAgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplexAgentServiceApplication.class, args);
    }
}
