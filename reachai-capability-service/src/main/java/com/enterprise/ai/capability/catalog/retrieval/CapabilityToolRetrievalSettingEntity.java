package com.enterprise.ai.capability.catalog.retrieval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_tool_retrieval_setting")
public class CapabilityToolRetrievalSettingEntity {

    public static final String SINGLETON_ID = "1";

    @TableId(type = IdType.INPUT)
    private String id;

    private String embeddingModelInstanceId;

    private LocalDateTime updatedAt;
}
