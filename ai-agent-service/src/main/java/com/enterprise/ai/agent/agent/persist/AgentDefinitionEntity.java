package com.enterprise.ai.agent.agent.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 定义表实体（Phase 3.0 从 JSON 文件迁到 DB）。
 * <p>
 * 与 {@link com.enterprise.ai.agent.agent.AgentDefinition} 是领域模型 ↔ 存储模型两件事：
 * 领域模型给 Java 业务代码（list/tools 直接是 List）；Entity 做数据库行映射（list 字段序列化成 JSON）。
 * 序列化 / 反序列化 & JSON 文件迁移由 {@code AgentDefinitionService} 统一负责。
 */
@Data
@TableName("agent_definition")
public class AgentDefinitionEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 人类可读的 slug，面向 {@code /api/v1/agents/{keySlug}/chat} 端点。 */
    private String keySlug;

    private String name;

    private String description;

    private String intentType;

    private String systemPrompt;

    /** tools 白名单 JSON 数组字符串。 */
    private String toolsJson;

    /** Skill 白名单 JSON 数组字符串（kind=SKILL 的 tool_definition.name），运行时与 tools 合并装配。 */
    private String skillsJson;

    private String modelName;

    private Integer maxSteps;

    private String type;

    /** Pipeline 子 Agent ID JSON 数组字符串。 */
    private String pipelineAgentIdsJson;

    private String knowledgeBaseGroupId;

    private String promptTemplateId;

    private String outputSchemaType;

    private String triggerMode;

    private Boolean useMultiAgentModel;

    private String extraJson;

    /** Agent Studio 画布节点/连线布局 JSON。 */
    private String canvasJson;

    private Boolean enabled;

    /** 是否允许调用 IRREVERSIBLE 副作用 Tool（护栏白名单）。 */
    private Boolean allowIrreversible;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
