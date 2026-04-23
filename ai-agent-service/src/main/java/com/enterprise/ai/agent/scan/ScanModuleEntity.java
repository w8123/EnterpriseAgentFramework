package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描项目下的模块实体。
 * 默认扫描完成后按 Controller 类名自动初始化；允许后续合并/重命名。
 */
@Data
@TableName("scan_module")
public class ScanModuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String name;

    private String displayName;

    /** 合并后聚合的 Controller 类名 JSON 数组，便于追源码与重建上下文。 */
    private String sourceClasses;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
