package com.enterprise.ai.control.slot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_slot_extract_log")
public class SlotExtractLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String skillName;

    private String fieldKey;

    private String extractorName;

    private Boolean hit;

    private String value;

    private Double confidence;

    private String evidence;

    private String userText;

    private Long latencyMs;

    private LocalDateTime createTime;
}
