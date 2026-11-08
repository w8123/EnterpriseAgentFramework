package com.enterprise.ai.control.mcp.infrastructure.runtime;

import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.mcp.application.usage.ModelCostSource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Tokens come from Runtime (owner of runtime_model_usage), prices from the model service. */
@Component
public class RuntimeModelCostSource implements ModelCostSource {

    private final RuntimeProxyClient runtimeClient;
    private final ControlModelCatalogClient modelClient;
    private final RuntimeUsageRequestSigner signer;

    public RuntimeModelCostSource(RuntimeProxyClient runtimeClient, ControlModelCatalogClient modelClient,
                                  RuntimeUsageRequestSigner signer) {
        this.runtimeClient = runtimeClient;
        this.modelClient = modelClient;
        this.signer = signer;
    }

    @Override
    public List<TokenRow> tokens(String projectCode, int days) {
        ResponseEntity<List<Map<String, Object>>> response = runtimeClient.runOpsModelTokens(
                signer.headers("/internal/runtime/usage/model-tokens"), projectCode, days);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("runtime model tokens returned "
                    + (response == null ? "nothing" : response.getStatusCode()));
        }
        List<Map<String, Object>> body = response.getBody();
        if (body == null) {
            return List.of();
        }
        return body.stream().map(row -> new TokenRow(day(row.get("day")), text(row.get("appId")),
                text(row.get("externalUserId")), text(row.get("userId")), text(row.get("projectCode")),
                text(row.get("entryType")), text(row.get("modelInstanceId")), number(row.get("calls")),
                number(row.get("inputTokens")), number(row.get("outputTokens")), number(row.get("runs")))).toList();
    }

    @Override
    public List<PriceRow> prices() {
        ResponseEntity<Map<String, Object>> response = modelClient.prices();
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("model prices returned "
                    + (response == null ? "nothing" : response.getStatusCode()));
        }
        if (!(response.getBody().get("data") instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(row -> new PriceRow(text(row.get("modelInstanceId")), text(row.get("name")),
                        text(row.get("modelName")), text(row.get("status")),
                        decimal(row.get("inputPricePerMillion")), decimal(row.get("outputPricePerMillion")),
                        text(row.get("currency"))))
                .toList();
    }

    static LocalDate day(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            try {
                return LocalDate.parse(text.trim().substring(0, Math.min(10, text.trim().length())));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (value instanceof List<?> parts && parts.size() >= 3) {
            return LocalDate.of(intOf(parts.get(0)), intOf(parts.get(1)), intOf(parts.get(2)));
        }
        return null;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
