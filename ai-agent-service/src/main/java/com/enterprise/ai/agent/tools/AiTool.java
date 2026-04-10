package com.enterprise.ai.agent.tools;

/**
 * 框架无关的 AI 工具接口（agent-service 本地兼容层）
 * <p>
 * 直接继承 ai-skill-sdk 的 {@link com.enterprise.ai.skill.AiTool}，
 * 现有 Tool 实现无需修改 import。
 * <p>
 * 新 Tool 建议直接实现 {@code com.enterprise.ai.skill.AiTool}。
 *
 * @deprecated 新 Tool 请直接实现 {@link com.enterprise.ai.skill.AiTool}
 */
@Deprecated
public interface AiTool extends com.enterprise.ai.skill.AiTool {
}
