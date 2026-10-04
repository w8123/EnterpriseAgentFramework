package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Console facade; request shape is shared, but confirmation and invocation ledger remain Console-owned. */
public final class HttpApiConsolePolicy {
    private HttpApiConsolePolicy() { }

    public static String unsupportedReason(JsonNode contract) {
        return HttpApiRequestPolicy.unsupportedReason(contract);
    }

    public static String connectionAuthReason(JsonNode contract, String mode, String type, String header) {
        return HttpApiRequestPolicy.connectionAuthReason(contract, mode, type, header);
    }

    public static BoundRequest bind(JsonNode contract, Map<String, Object> path, Map<String, Object> query,
                                    Map<String, Object> body, ObjectMapper mapper) {
        var request = HttpApiRequestPolicy.bind(contract, path, query, body, mapper);
        return new BoundRequest(request.method(), request.sideEffect(), request.encodedRoute(),
                request.queryParams(), request.jsonBody());
    }

    public record BoundRequest(String method, String sideEffect, String encodedRoute,
                               Map<String, String> queryParams, Map<String, Object> jsonBody) { }
}
