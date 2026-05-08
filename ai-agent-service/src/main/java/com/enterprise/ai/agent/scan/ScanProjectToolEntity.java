package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描项目下的接口定义；「添加为 Tool」后在 {@code tool_definition} 增加一条并写入
 * {@link #globalToolDefinitionId}，本行仍保留供扫描结果页展示与项目内测试。
 */
@Data
@TableName("scan_project_tool")
public class ScanProjectToolEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long moduleId;

    private String name;

    private String description;

    private String parametersJson;

    private String source;

    private String sourceLocation;

    private String httpMethod;

    private String baseUrl;

    private String contextPath;

    private String endpointPath;

    private String requestBodyType;

    private String responseType;

    private String aiDescription;

    /** Java 侧 @AiCapability 声明的原始结构化元数据 JSON。 */
    private String capabilityMetadataJson;

    private Boolean enabled;

    private Boolean agentVisible;

    private Boolean lightweightEnabled;

    /** 已注册为全局 Tool 时非空，对应 {@code tool_definition.id} */
    private Long globalToolDefinitionId;

    /**
     * 为 true 表示当前磁盘扫描或 SDK 上报中已不再包含该接口（墓碑行），
     * 若仍关联 {@link #globalToolDefinitionId} 则全局 Tool 可能仍存在或已被禁用。
     */
    private Boolean removedFromSource;

    /** {@link #removedFromSource} 置为 true 的时间 */
    private LocalDateTime removedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
