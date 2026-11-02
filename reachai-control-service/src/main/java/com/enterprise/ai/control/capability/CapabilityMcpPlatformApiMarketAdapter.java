package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformApiMarketPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Runs API market calls through the controller a person uses on the API market page. */
@Component
@RequiredArgsConstructor
final class CapabilityMcpPlatformApiMarketAdapter implements McpPlatformApiMarketPort {
    private final ApiMarketConsoleController market;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> read(PlatformAuthenticatedSession actor, Read read, String key, Map<String, String> query) {
        return scope.run(actor, request -> switch (read) {
            case ENTRIES -> market.entries(request, query);
            case ENTRY -> market.entry(request, key);
            // The discovery handler picks its owner path from the request URI.
            case STATS, CATEGORIES, SOURCES -> market.discovery(at(request, "/api/api-market/" + read.name().toLowerCase()));
            case INTEGRATIONS -> market.integrations(request, Long.valueOf(query.get("projectId")), query.get("projectCode"), query.get("status"));
            case INTEGRATION -> market.integration(request, Long.parseLong(key));
        });
    }

    @Override
    public ResponseEntity<?> select(PlatformAuthenticatedSession actor, String entryKey, Map<String, Object> body) {
        return scope.run(actor, request -> market.select(request, entryKey, body));
    }

    @Override
    public ResponseEntity<?> updateStatus(PlatformAuthenticatedSession actor, long integrationId, Map<String, Object> body) {
        return scope.run(actor, request -> market.status(request, integrationId, body));
    }

    private static HttpServletRequest at(HttpServletRequest request, String uri) {
        return new HttpServletRequestWrapper(request) {
            @Override public String getRequestURI() { return uri; }
        };
    }
}
