package com.enterprise.ai.agent.skill.slot.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("slot_extract_log")
public class SlotExtractLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String skillName;

    private String fieldKey;

    /** {@code SlotExtractor.name()}，未命中时填 LLM_FALLBACK / DETERMINISTIC 等。 */
    private String extractorName;

    /** 是否命中（即提取出非空值）。 */
    private Boolean hit;

    /** 命中值（已 toString），未命中为 null。 */
    private String value;

    private Double confidence;

    private String evidence;

    /** 用户原文，用于排查；最多 4000 字。 */
    private String userText;

    private Long latencyMs;

    private LocalDateTime createTime;
}
