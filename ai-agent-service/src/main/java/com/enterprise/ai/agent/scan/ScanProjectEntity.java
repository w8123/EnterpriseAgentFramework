package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scan_project")
public class ScanProjectEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String baseUrl;

    private String contextPath;

    private String scanPath;

    private String scanType;

    private String specFile;

    private Integer toolCount;

    private String status;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
