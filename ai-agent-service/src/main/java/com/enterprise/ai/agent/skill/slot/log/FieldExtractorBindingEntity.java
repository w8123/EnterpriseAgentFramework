package com.enterprise.ai.agent.skill.slot.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code (skill_name, field_key)} → 启用的提取器名称白名单（JSON 数组）。
 * 未配置时按 {@link com.enterprise.ai.agent.skill.slot.extractor.SlotExtractor#priority()} 全跑。
 */
@Data
@TableName("field_extractor_binding")
public class FieldExtractorBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skillName;

    private String fieldKey;

    /** JSON 数组字符串，例如 {@code ["time","dept"]}。 */
    private String extractorNamesJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
