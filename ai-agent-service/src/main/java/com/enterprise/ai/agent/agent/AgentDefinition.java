package com.enterprise.ai.agent.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 定义模型 — 描述一个可复用的智能体配置
 * <p>
 * 包含 Agent 的名称、System Prompt、关联的工具集、模型参数等。
 * 可通过管理 API 进行 CRUD 操作。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {

    private String id;

    /**
     * 人类可读的 slug，面向 {@code /api/v1/agents/{keySlug}/chat} 端点。
     * Phase 3.0 新增；旧数据为空时由 {@code AgentDefinitionService} 自动补齐为 id。
     */
    private String keySlug;

    private String name;

    private String description;

    /** 意图类型映射（如 KNOWLEDGE_QA、QUERY_DATA），用于意图路由 */
    private String intentType;

    /** Agent 的 System Prompt */
    private String systemPrompt;

    /** Agent 可使用的工具名列表 */
    private List<String> tools;

    /**
     * Agent 可调用的 Skill 名列表（与 {@link #tools} 并列存储，运行时合并为 Toolkit 白名单）。
     */
    @Builder.Default
    private List<String> skills = List.of();

    /** 使用的模型名称（默认继承全局配置） */
    private String modelName;

    /** Agent 最大推理步数 */
    @Builder.Default
    private int maxSteps = 5;

    /** 是否启用（禁用后意图路由会跳过此 Agent） */
    @Builder.Default
    private boolean enabled = true;

    /** Agent 类型：single（单 Agent）、pipeline（多 Agent 流水线） */
    @Builder.Default
    private String type = "single";

    /** Pipeline 类型 Agent 的子 Agent ID 列表（按执行顺序排列） */
    private List<String> pipelineAgentIds;

    /** 关联的知识库组 ID（Agent 检索时使用） */
    private String knowledgeBaseGroupId;

    /** 关联的 Prompt 模板 ID（可覆盖 systemPrompt） */
    private String promptTemplateId;

    /** 输出 Schema 类型名（如 ReviewResult、ExtractResult），为空则返回纯文本 */
    private String outputSchemaType;

    /** 触发方式：chat（对话）、api（API 调用）、event（事件驱动）、all（全部） */
    @Builder.Default
    private String triggerMode = "all";

    /** 使用多 Agent 模型（Pipeline 场景下的子 Agent 应设为 true） */
    @Builder.Default
    private boolean useMultiAgentModel = false;

    /** 额外参数 */
    private Map<String, Object> extra;

    /** Agent Studio 画布 JSON（节点 + 连线布局）；Phase 3.0 新增。 */
    private String canvasJson;

    /**
     * 是否允许调用 IRREVERSIBLE 副作用 Tool（护栏白名单）。
     * 默认 false；由运营在 Studio 发布时显式确认后置为 true。
     */
    @Builder.Default
    private boolean allowIrreversible = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
