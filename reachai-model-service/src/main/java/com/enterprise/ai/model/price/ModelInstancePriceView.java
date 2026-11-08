package com.enterprise.ai.model.price;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A model's price, with enough of the model to name it on the usage page. */
public record ModelInstancePriceView(String modelInstanceId,
                                     String name,
                                     String modelName,
                                     String provider,
                                     String modelType,
                                     String status,
                                     BigDecimal inputPricePerMillion,
                                     BigDecimal outputPricePerMillion,
                                     String currency,
                                     LocalDateTime updatedAt) {
}
