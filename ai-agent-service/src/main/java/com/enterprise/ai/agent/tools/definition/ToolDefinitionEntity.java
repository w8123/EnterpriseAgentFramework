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

    private Long moduleId;

    private Boolean enabled;

    private Boolean agentVisible;

    /** 副作用等级：NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE。 */
    private String sideEffect;

    /** 仅 kind=SKILL 有值：SUB_AGENT / WORKFLOW / AUGMENTED_TOOL。 */
    private String skillKind;

    private Boolean lightweightEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
