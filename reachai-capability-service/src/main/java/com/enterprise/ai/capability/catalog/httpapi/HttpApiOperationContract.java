package com.enterprise.ai.capability.catalog.httpapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Structured, secret-free source contract for one HTTP operation.
 *
 * <p>There are intentionally no base URL, credential reference, cookie value, example, or
 * display-name fields. Mapping conditions retain only route-selection semantics and reject
 * credential-bearing values. Source adapters may retain source evidence elsewhere when their
 * owner requires it, but it cannot become part of the HTTP API identity or contract hash.</p>
 */
public record HttpApiOperationContract(
        String httpMethod,
        String contextPath,
        String endpointPath,
        MappingConditions mappingConditions,
        List<Parameter> parameters,
        RequestBody requestBody,
        List<Response> responses,
        AuthenticationRequirement authentication,
        String sideEffect
) {
    public HttpApiOperationContract {
        parameters = immutableList(parameters);
        responses = immutableList(responses);
    }

    public record MappingConditions(
            List<String> consumes,
            List<String> produces,
            List<MappingCondition> conditions
    ) {
        public MappingConditions {
            consumes = immutableList(consumes);
            produces = immutableList(produces);
            conditions = immutableList(conditions);
        }
    }

    /**
     * One lossless Spring mapping predicate. Repeated names are allowed because each predicate
     * is a separate fact, for example {@code X-Mode} present and {@code X-Mode != legacy}.
     */
    public record MappingCondition(
            HttpApiMappingConditionKind kind,
            String name,
            HttpApiMappingConditionOperator operator,
            String value
    ) {
    }

    public record Parameter(
            String name,
            HttpApiParameterLocation location,
            boolean required,
            JsonNode schema,
            List<String> contentTypes
    ) {
        public Parameter {
            contentTypes = immutableList(contentTypes);
        }
    }

    public record RequestBody(
            boolean required,
            JsonNode schema,
            List<String> contentTypes
    ) {
        public RequestBody {
            contentTypes = immutableList(contentTypes);
        }
    }

    public record Response(
            String status,
            JsonNode schema,
            List<String> contentTypes
    ) {
        public Response {
            contentTypes = immutableList(contentTypes);
        }
    }

    public record AuthenticationRequirement(
            boolean required,
            String state,
            List<String> schemes,
            List<String> requiredHeaderNames
    ) {
        public AuthenticationRequirement {
            schemes = immutableList(schemes);
            requiredHeaderNames = immutableList(requiredHeaderNames);
        }

        /** Existing discovery sources that do know the answer retain their prior two-state form. */
        public AuthenticationRequirement(boolean required, List<String> schemes, List<String> requiredHeaderNames) {
            this(required, required ? "REQUIRED" : "NONE", schemes, requiredHeaderNames);
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
