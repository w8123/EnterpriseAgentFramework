package com.enterprise.ai.control.mcp.application.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Token usage (owned by Runtime) and model prices (owned by the model service) behind 「费用」. */
public interface ModelCostSource {

    List<TokenRow> tokens(String projectCode, int days);

    List<PriceRow> prices();

    /** Tokens grouped by day, person, system, entry and model. Person fields are null outside a run. */
    record TokenRow(LocalDate day, String appId, String externalUserId, String userId, String projectCode,
                    String entryType, String modelInstanceId, long calls, long inputTokens, long outputTokens,
                    long runs) {
    }

    record PriceRow(String modelInstanceId, String name, String modelName, String status,
                    BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, String currency) {
    }
}
