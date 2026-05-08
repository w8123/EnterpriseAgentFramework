package com.enterprise.ai.agent.tools.definition;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_definition")
public class ToolDefinitionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 能力形态：TOOL / SKILL。默认 TOOL；Phase 2.0 新增。 */
    private String kind;

    private String description;

    private String aiDescription;

    /** Java 侧 @AiCapability 声明的原始结构化元数据 JSON。 */
    private String capabilityMetadataJson;

    private String parametersJson;

    /** Skill 专属 spec JSON（SubAgent: {systemPrompt, toolWhitelist, llmProvider, llmModel, maxSteps}）。 */
    private String specJson;

    private String source;

    private String sourceLocation;

    private String httpMethod;

    private String baseUrl;

    private String contextPath;

    private String endpointPath;

    private String requestBodyType;

    private String responseType;

    private Long projectId;

    /** 冗余项目编码，便于 SDK 注册、跨项目引用和后续脱离自增 ID 的导入导出。 */
    private String projectCode;

    /** 项目内能力可见性：PRIVATE / PROJECT / SHARED / PUBLIC。 */
    private String visibility;

    /** 稳定能力全名，建议形如 projectCode:name；旧数据可为空并按 name 兼容。 */
    private String qualifiedName;

    private Long moduleId;

    private Boolean enabled;

    private Boolean agentVisible;

    /** 副作用等级：NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE。 */
    private String sideEffect;

    /** 仅 kind=SKILL 有值：SUB_AGENT / WORKFLOW / AUGMENTED_TOOL。 */
    private String skillKind;

    /** kind=SKILL 时：true 表示草稿暂存，不落 ToolRegistry、不可执行。 */
    private Boolean draft;

    private Boolean lightweightEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
