package com.enterprise.ai.agent.capability.catalog.tool.retrieval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单例表：记录 Tool 向量索引重建时选用的 Embedding 模型实例，供运行时语义召回（用户问题向量化）读取。
 */
@Data
@TableName("tool_retrieval_setting")
public class ToolRetrievalSettingEntity {

    public static final String SINGLETON_ID = "1";

    @TableId(type = IdType.INPUT)
    private String id;

    private String embeddingModelInstanceId;

    private LocalDateTime updatedAt;
}
