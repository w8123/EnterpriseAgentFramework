package com.enterprise.ai.runtime;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan(value = {
        "com.enterprise.ai.runtime.credential",
        "com.enterprise.ai.runtime.debug",
        "com.enterprise.ai.runtime.execution",
        "com.enterprise.ai.runtime.interaction",
        "com.enterprise.ai.runtime.eval",
        "com.enterprise.ai.runtime.runops",
        "com.enterprise.ai.runtime.trace",
        "com.enterprise.ai.runtime.workflow"
}, annotationClass = Mapper.class)
@EnableFeignClients(basePackages = "com.enterprise.ai.runtime.client")
@EnableAsync
@EnableScheduling
public class ReachAiRuntimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReachAiRuntimeServiceApplication.class, args);
    }
}
