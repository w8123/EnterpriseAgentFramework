package com.enterprise.ai.agent.capability.catalog.scan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描项目下的接口定义；纳入能力目录后会在 {@code capability_tool_definition} 生成运行时调用投影并写入
 * {@link #globalToolDefinitionId}，本行仍保留供扫描结果页展示与项目内测试。字段名沿用兼容契约，
 * 不表示存在独立的“全局 Tool”产品资产。
 */
@Data
@TableName("capability_scan_project_tool")
public class ScanProjectToolEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long moduleId;

    private String name;

    /** 用户可读的简短名称；name 仍是稳定机器标识。 */
    private String title;

    private String description;

    private String parametersJson;

    private String source;

    private String sourceLocation;

    /** 服务端绑定的来源能力标识；由注册治理维护，不能通过目录编辑改变。 */
    private String sourceQualifiedName;

    /** 来源资产类型投影：BUSINESS_METHOD / HTTP_API / UNCLASSIFIED。 */
    private String assetType;

    private String httpMethod;

    private String baseUrl;

    private String contextPath;

    private String endpointPath;

    private String requestBodyType;

    private String responseType;

    private String aiDescription;

    /** Java 侧 @ReachCapability 声明的原始结构化元数据 JSON。 */
    private String capabilityMetadataJson;

    /** LLM 敏感数据扫描结果 JSON，见 {@link com.enterprise.ai.capability.catalog.scan.CapabilitySensitiveDataStored} */
    private String sensitiveDataJson;

    private Boolean enabled;

    /** 已生成运行时调用投影时非空，对应 {@code capability_tool_definition.id}。 */
    private Long globalToolDefinitionId;

    /**
     * 为 true 表示当前磁盘扫描或 SDK 上报中已不再包含该接口（墓碑行），
     * 若仍关联 {@link #globalToolDefinitionId}，则对应运行时调用投影可能仍存在或已被禁用。
     */
    private Boolean removedFromSource;

    /** {@link #removedFromSource} 置为 true 的时间 */
    private LocalDateTime removedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
