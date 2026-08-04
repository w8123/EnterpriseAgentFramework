package com.enterprise.ai.control.aicoding.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "reachai.ai-coding-task")
public class AiCodingTaskProperties {

    private Duration activationTtl = Duration.ofHours(72);
    private Duration tokenTtl = Duration.ofHours(72);
    private Duration connectionLease = Duration.ofMinutes(5);
    private int maxActivationAttempts = 5;
    private String secretPepper = "reachai-local-dev-ai-coding-task-pepper";
}
