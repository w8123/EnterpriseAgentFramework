package com.enterprise.ai.agent.capability;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interaction_event")
public class InteractionEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String eventType;

    private String payloadJson;

    private String operatorId;

    private LocalDateTime createTime;
}
