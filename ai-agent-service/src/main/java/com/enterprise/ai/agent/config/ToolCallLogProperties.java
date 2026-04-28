package com.enterprise.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tool 调用审计日志配置（{@code ai.tool-call-log.*}）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.tool-call-log")
public class ToolCallLogProperties {

    /** 主开关，关闭后不再写入 {@code tool_call_log}。 */
    private boolean enabled = true;

    /** 是否异步写入，避免拖慢 Agent 主链路。 */
    private boolean async = true;

    /** 单条日志 result_summary 的最大字符数。 */
    private int resultMaxChars = 2000;

    /** 单条日志 args_json 的最大字符数。 */
    private int argsMaxChars = 4000;

    /**
     * LLM trace 中每个 tool 的 {@code parameters} 快照最大字符数（序列化 JSON 后截断），
     * 避免单工具 schema 占满整条 {@code args_json}。
     */
    private int toolParametersSnapshotMaxChars = 2500;
}
