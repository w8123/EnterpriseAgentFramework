package com.enterprise.ai.capability;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.enterprise.ai.capability",
        "com.enterprise.ai.agent.registry",
        "com.enterprise.ai.agent.capability"
})
@MapperScan(value = {
        "com.enterprise.ai.agent.registry",
        "com.enterprise.ai.agent.capability",
        "com.enterprise.ai.agent.capability.catalog.semantic",
        "com.enterprise.ai.agent.capability.catalog.domain",
        "com.enterprise.ai.agent.capability.catalog.graph",
        "com.enterprise.ai.agent.capability.catalog.scan",
        "com.enterprise.ai.agent.capability.catalog.tool.definition",
        "com.enterprise.ai.capability.catalog.composition",
        "com.enterprise.ai.capability.catalog.mining",
        "com.enterprise.ai.capability.catalog.retrieval"
}, annotationClass = Mapper.class)
@EnableFeignClients
@EnableAsync
@EnableScheduling
public class ReachAiCapabilityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReachAiCapabilityServiceApplication.class, args);
    }
}
