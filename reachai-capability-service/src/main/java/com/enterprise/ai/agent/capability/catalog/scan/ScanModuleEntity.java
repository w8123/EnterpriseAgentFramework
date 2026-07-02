package com.enterprise.ai.agent.capability.catalog.scan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scan_module")
public class ScanModuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String name;

    private String displayName;

    private String sourceClasses;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
