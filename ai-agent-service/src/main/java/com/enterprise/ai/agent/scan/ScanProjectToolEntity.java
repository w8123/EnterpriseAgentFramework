package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描项目下的接口定义；与全局 {@code tool_definition} 分离，仅在「添加为 Tool」后写入全局表。
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

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
