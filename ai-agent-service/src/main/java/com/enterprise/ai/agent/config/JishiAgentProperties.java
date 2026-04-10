package com.enterprise.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极视角平台多智能体配置
 * <p>
 * 支持注册多个极视角智能体应用，每个智能体对应一个 appId。
 * 通过 agentKey 引用智能体，新增智能体只需在 yml 中添加一组配置。
 *
 * <pre>
 * jishi:
 *   platform:
 *     agents:
 *       my-agent:
 *         app-id: e72d1d39-...
 *         name: 我的智能体
 *         description: 用途说明
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "jishi.platform")
public class JishiAgentProperties {

    private String baseUrl;

    private String apiKey;

    /** HTTP请求超时(ms) */
    private long timeout = 30000;

    /**
     * 已注册的极视角智能体，Key 为自定义的智能体标识（agentKey），
     * 在代码中通过此 Key 引用特定智能体
     */
    private Map<String, AgentDefinition> agents = new LinkedHashMap<>();

    /**
     * 单个极视角智能体的定义
     */
    @Data
    public static class AgentDefinition {

        /** 极视角平台分配的应用ID，构成API路径的一部分 */
        private String appId;

        /** 智能体名称（便于识别和日志输出） */
        private String name;

        /** 智能体用途描述 */
        private String description;
    }
}
