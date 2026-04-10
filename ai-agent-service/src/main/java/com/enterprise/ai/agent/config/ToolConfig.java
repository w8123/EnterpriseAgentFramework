package com.enterprise.ai.agent.config;

import org.springframework.context.annotation.Configuration;

/**
 * AI Tool 配置
 * <p>
 * 所有 Tool 实现类已标记 @Component + 实现 AiTool 接口，
 * 由 Spring 自动扫描注册，并通过 ToolRegistry 统一管理。
 * <p>
 * 如需额外的 Tool 相关配置（如权限控制、超时设置），在此扩展。
 */
@Configuration
public class ToolConfig {
}
