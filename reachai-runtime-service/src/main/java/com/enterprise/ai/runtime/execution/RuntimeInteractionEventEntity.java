package com.enterprise.ai.runtime.execution;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_interaction_event")
public class RuntimeInteractionEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String eventType;

    private String payloadJson;

    private String operatorId;

    private LocalDateTime createTime;
}
