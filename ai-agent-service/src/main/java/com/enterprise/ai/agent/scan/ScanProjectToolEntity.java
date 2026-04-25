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

    private Boolean enabled;

    private Boolean agentVisible;

    private Boolean lightweightEnabled;

    /** 已注册为全局 Tool 时非空，对应 {@code tool_definition.id} */
    private Long globalToolDefinitionId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
