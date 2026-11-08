package com.enterprise.ai.model.price;

import java.math.BigDecimal;

public record ModelInstancePriceRequest(BigDecimal inputPricePerMillion,
                                        BigDecimal outputPricePerMillion,
                                        String currency) {
}
