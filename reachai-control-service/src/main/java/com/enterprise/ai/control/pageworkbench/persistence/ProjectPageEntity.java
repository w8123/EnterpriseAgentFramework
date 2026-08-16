package com.enterprise.ai.control.pageworkbench.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_project_page")
public class ProjectPageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String projectCode;
    private String pageKey;
    private String moduleKey;
    private String moduleName;
    private String name;
    private String description;
    private String routePattern;
    private String businessPageUrl;
    private String componentPath;
    private String sourceType;
    private String lifecycleStatus;
    private LocalDateTime lastDiscoveredAt;
    private LocalDateTime lastVerifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
