package com.enterprise.ai.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.enterprise.ai.control.client")
@EnableAsync
@EnableScheduling
public class ReachAiControlServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReachAiControlServiceApplication.class, args);
    }
}
