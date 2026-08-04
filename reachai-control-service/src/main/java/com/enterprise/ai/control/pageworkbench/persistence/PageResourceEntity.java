package com.enterprise.ai.control.pageworkbench.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_project_page_resource")
public class PageResourceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pageId;
    private String projectCode;
    private String resourceType;
    private String resourceKey;
    private String displayName;
    private String location;
    private String httpMethod;
    private String accessMode;
    private String metadataJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
