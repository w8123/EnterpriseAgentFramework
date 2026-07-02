package com.enterprise.ai.control.slot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("field_extractor_binding")
public class FieldExtractorBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skillName;

    private String fieldKey;

    private String extractorNamesJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
