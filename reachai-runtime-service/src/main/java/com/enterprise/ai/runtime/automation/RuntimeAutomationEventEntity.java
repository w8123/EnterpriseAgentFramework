package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_automation_event")
public class RuntimeAutomationEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long automationId;
    private Long occurrenceId;
    private String eventType;
    private String actorType;
    private String actorId;
    private String detailJson;
    private LocalDateTime createdAt;
}
