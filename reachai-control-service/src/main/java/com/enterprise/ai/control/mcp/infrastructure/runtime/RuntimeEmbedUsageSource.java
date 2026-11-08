package com.enterprise.ai.control.mcp.infrastructure.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.mcp.application.usage.EmbedUsageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class RuntimeEmbedUsageSource implements EmbedUsageSource {

    private final RuntimeProxyClient runtimeClient;

    public RuntimeEmbedUsageSource(RuntimeProxyClient runtimeClient) {
        this.runtimeClient = runtimeClient;
    }

    @Override
    public List<EmbedUserRow> embedUsers(String projectCode, int days) {
        ResponseEntity<List<Map<String, Object>>> response = runtimeClient.runOpsEmbedUserUsage(projectCode, days);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("runtime embed usage returned "
                    + (response == null ? "nothing" : response.getStatusCode()));
        }
        List<Map<String, Object>> body = response.getBody();
        if (body == null) {
            return List.of();
        }
        return body.stream()
                .map(row -> new EmbedUserRow(text(row.get("appId")), text(row.get("projectCode")),
                        text(row.get("externalUserId")), number(row.get("runCount")),
                        number(row.get("sessionCount")), number(row.get("failedCount")),
                        time(row.get("lastStartedAt"))))
                .filter(row -> row.externalUserId() != null)
                .toList();
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

    /** Runtime serializes LocalDateTime as ISO text (or, with timestamps on, as an array). */
    public static LocalDateTime time(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            try {
                return LocalDateTime.parse(text.trim().replace(' ', 'T'));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (value instanceof List<?> parts && parts.size() >= 3) {
            int[] values = new int[6];
            for (int index = 0; index < Math.min(parts.size(), 6); index++) {
                values[index] = parts.get(index) instanceof Number number ? number.intValue() : 0;
            }
            return LocalDateTime.of(values[0], values[1], values[2], values[3], values[4], values[5]);
        }
        return null;
    }
}
