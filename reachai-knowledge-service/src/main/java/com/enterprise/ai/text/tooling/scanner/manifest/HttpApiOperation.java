package com.enterprise.ai.text.tooling.scanner.manifest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Secret-free HTTP operation fact discovered from a source scanner.
 *
 * <p>This is deliberately separate from {@link ToolDefinition}: a scanner may keep its legacy
 * Tool view for compatibility, while the HTTP API inventory retains route variants and mapping
 * predicates without using a Tool name as identity.</p>
 */
public record HttpApiOperation(
        String sourceKey,
        String sourceLocation,
        String sourceRevision,
        String httpMethod,
        String contextPath,
        String endpointPath,
        List<String> consumes,
        List<String> produces,
        List<MappingCondition> mappingConditions,
        List<Parameter> parameters,
        RequestBody requestBody,
        List<Response> responses,
        String authenticationState,
        List<String> authenticationSchemes,
        List<String> requiredHeaderNames,
        String sideEffect
) {
    public HttpApiOperation {
        consumes = immutableList(consumes);
        produces = immutableList(produces);
        mappingConditions = immutableList(mappingConditions);
        parameters = immutableList(parameters);
        responses = immutableList(responses);
        authenticationSchemes = immutableList(authenticationSchemes);
        requiredHeaderNames = immutableList(requiredHeaderNames);
    }

    public record MappingCondition(String kind, String name, String operator, String value) {
    }

    public record Parameter(String name, String location, Boolean required, JsonNode schema,
                            List<String> contentTypes) {
        public Parameter {
            contentTypes = immutableList(contentTypes);
        }
    }

    public record RequestBody(String location, Boolean required, JsonNode schema, List<String> contentTypes) {
        public RequestBody {
            contentTypes = immutableList(contentTypes);
        }
    }

    public record Response(String status, JsonNode schema, List<String> contentTypes) {
        public Response {
            contentTypes = immutableList(contentTypes);
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
