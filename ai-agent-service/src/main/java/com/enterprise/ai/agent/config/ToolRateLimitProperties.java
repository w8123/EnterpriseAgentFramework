package com.enterprise.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tool / Skill 调用限流配置（{@code ai.tool-rate-limit.*}）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.tool-rate-limit")
public class ToolRateLimitProperties {

    /** 主开关，关闭后不做限流。 */
    private boolean enabled = false;

    /** 每个窗口内允许的调用次数。 */
    private int capacity = 120;

    /** 固定窗口长度，单位秒。 */
    private int windowSeconds = 60;

    /** 是否按 userId 拆分限流桶。 */
    private boolean perUser = true;

    /** 是否按 roles 拆分限流桶。 */
    private boolean perRoles = false;
}
