package com.enterprise.ai.agent.capability.catalog.tool.definition;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_tool_definition")
public class ToolDefinitionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 用户可读的简短名称；name 仍是稳定机器标识。 */
    private String title;

    private String description;

    private String aiDescription;

    /** Java 侧 @ReachCapability 声明的原始结构化元数据 JSON。 */
    private String capabilityMetadataJson;

    private String parametersJson;

    private String source;

    private String sourceLocation;

    /** capability_source_state.qualified_name；独立于展示位置和可调用名称的来源归属。 */
    private String sourceQualifiedName;

    /** 源资产的明确分类；调用定义由接纳资产派生。 */
    private String assetType;

    private String httpMethod;

    private String baseUrl;

    private String contextPath;

    private String endpointPath;

    private String requestBodyType;

    private String responseType;

    private Long projectId;

    /** 冗余项目编码，便于 SDK 注册、跨项目引用和后续脱离自增 ID 的导入导出。 */
    private String projectCode;

    /** 稳定能力全名；项目能力建议形如 projectCode:name，全局能力可直接按 name 引用。 */
    private String qualifiedName;

    private Long moduleId;

    private Boolean enabled;

    /** 副作用等级：NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE。 */
    private String sideEffect;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
