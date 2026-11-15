package com.enterprise.ai.control.activity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_activity_setting")
public class ActivitySettingEntity {

    @TableId(type = IdType.INPUT)
    private String projectCode;
    private Boolean enabled;
    private Boolean captureSearch;
    private Boolean hideUserNames;
    private Integer retentionDays;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
