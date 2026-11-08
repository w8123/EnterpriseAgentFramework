package com.enterprise.ai.model.price;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Price per million tokens for one model instance; only used to estimate cost in 「AI 使用情况」. */
@Data
@TableName("model_instance_price")
public class ModelInstancePriceEntity {

    @TableId(type = IdType.INPUT)
    private String modelInstanceId;

    private BigDecimal inputPricePerMillion;

    private BigDecimal outputPricePerMillion;

    private String currency;

    private LocalDateTime updatedAt;
}
