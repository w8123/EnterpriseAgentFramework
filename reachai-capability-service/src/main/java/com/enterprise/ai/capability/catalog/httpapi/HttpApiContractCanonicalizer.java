package com.enterprise.ai.capability.catalog.httpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Produces deterministic, secret-free HTTP operation identity and contract payloads.
 *
 * <p>The canonical representation is deliberately independent of source location, source
 * revision, display name and JSON property order. It contains no transport configuration or
 * secret value.</p>
 */
@Component
public class HttpApiContractCanonicalizer {

    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> SUPPORTED_SIDE_EFFECTS = Set.of(
            "NONE", "READ_ONLY", "IDEMPOTENT_WRITE", "WRITE", "IRREVERSIBLE");
    // Sample/default annotations can carry production request material. They are stripped from
    // the stable contract while schema constraints such as enum/const remain semantic.
    private static final Set<String> SAMPLE_SCHEMA_KEYWORDS = Set.of(
            "default", "example", "examples");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:base[_-]?url|credentialref|password|token|secret|api[_-]?key)\\s*(?:=|:)|"
                    + "authorization\\s*(?:=|:)\\s*bearer|cookie\\s*(?:=|:)");

    private final ObjectMapper objectMapper;

    public HttpApiContractCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public CanonicalHttpApiContract canonicalize(HttpApiServiceScope scope,
                                                  HttpApiOperationContract operation) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");

        String method = httpMethod(operation.httpMethod());
        String routeTemplate = joinRoute(normalizeContextPath(operation.contextPath()),
                normalizeEndpointPath(operation.endpointPath()));
        CanonicalMappingConditions conditions = mappingConditions(operation.mappingConditions());
        List<CanonicalParameter> parameters = parameters(operation.parameters());
        CanonicalRequestBody requestBody = requestBody(operation.requestBody());
        List<CanonicalResponse> responses = responses(operation.responses());
        CanonicalAuthentication authentication = authentication(operation.authentication());
        String sideEffect = sideEffect(operation.sideEffect());

        CanonicalIdentity identity = new CanonicalIdentity(method, routeTemplate, conditions);
        HttpApiServiceScope.StableScope stableScope = scope.stableScope();
        String identityHash = sha256(canonicalJson(new ScopedIdentity(stableScope, identity)));
        CanonicalContract contract = new CanonicalContract(
                stableScope, identity, parameters, requestBody, responses, authentication, sideEffect);
        String contractJson = canonicalJson(contract);
        String mappingConditionsJson = canonicalJson(conditions);
        return new CanonicalHttpApiContract(
                scope,
                identityHash,
                scope.qualifiedNamePrefix() + identityHash,
                method,
                routeTemplate,
                mappingConditionsJson,
                sha256(contractJson),
                contractJson);
    }

    private CanonicalMappingConditions mappingConditions(HttpApiOperationContract.MappingConditions raw) {
        if (raw == null) {
            return new CanonicalMappingConditions(List.of(), List.of(), List.of());
        }
        return new CanonicalMappingConditions(
                normalizedMediaTypes(raw.consumes()),
                normalizedMediaTypes(raw.produces()),
                normalizedConditions(raw.conditions()));
    }

    private List<CanonicalParameter> parameters(List<HttpApiOperationContract.Parameter> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<CanonicalParameter> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (HttpApiOperationContract.Parameter parameter : raw) {
            if (parameter == null || parameter.location() == null) {
                throw new IllegalArgumentException("HTTP API parameter and location are required");
            }
            String name = identifier(parameter.name(), "HTTP API parameter name");
            String normalizedName = parameter.location() == HttpApiParameterLocation.HEADER
                    ? name.toLowerCase(Locale.ROOT) : name;
            String key = parameter.location().name() + ":" + normalizedName;
            if (!seen.add(key)) {
                throw new IllegalArgumentException("HTTP API parameter identity is duplicated: " + key);
            }
            result.add(new CanonicalParameter(
                    normalizedName,
                    parameter.location().name(),
                    parameter.required(),
                    schema(parameter.schema(), sensitiveName(parameter.name())),
                    normalizedMediaTypes(parameter.contentTypes())));
        }
        result.sort(Comparator.comparing(CanonicalParameter::location)
                .thenComparing(CanonicalParameter::name)
                .thenComparing(item -> canonicalJson(item.schema())));
        return List.copyOf(result);
    }

    private CanonicalRequestBody requestBody(HttpApiOperationContract.RequestBody raw) {
        if (raw == null) {
            return null;
        }
        return new CanonicalRequestBody(raw.required(), schema(raw.schema(), false), normalizedMediaTypes(raw.contentTypes()));
    }

    private List<CanonicalResponse> responses(List<HttpApiOperationContract.Response> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<CanonicalResponse> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (HttpApiOperationContract.Response response : raw) {
            if (response == null) {
                throw new IllegalArgumentException("HTTP API response cannot be null");
            }
            String status = responseStatus(response.status());
            String key = status + "|" + canonicalJson(schema(response.schema(), false))
                    + "|" + canonicalJson(normalizedMediaTypes(response.contentTypes()));
            if (!seen.add(key)) {
                throw new IllegalArgumentException("HTTP API response contract is duplicated: " + status);
            }
            result.add(new CanonicalResponse(status, schema(response.schema(), false),
                    normalizedMediaTypes(response.contentTypes())));
        }
        result.sort(Comparator.comparing(CanonicalResponse::status)
                .thenComparing(item -> canonicalJson(item.schema()))
                .thenComparing(item -> canonicalJson(item.contentTypes())));
        return List.copyOf(result);
    }

    private CanonicalAuthentication authentication(HttpApiOperationContract.AuthenticationRequirement raw) {
        if (raw == null) {
            return new CanonicalAuthentication("UNKNOWN", List.of(), List.of());
        }
        String state = authenticationState(raw.state(), raw.required());
        List<String> schemes = normalizedText(raw.schemes(), "authentication scheme", true);
        List<String> headers = new ArrayList<>();
        for (String header : raw.requiredHeaderNames() == null ? List.<String>of() : raw.requiredHeaderNames()) {
            headers.add(headerName(header));
        }
        headers = headers.stream().distinct().sorted().toList();
        if ("UNKNOWN".equals(state) && (!schemes.isEmpty() || !headers.isEmpty())) {
            throw new IllegalArgumentException("HTTP API unknown authentication cannot declare schemes or headers");
        }
        return new CanonicalAuthentication(state, schemes, headers);
    }

    private String authenticationState(String raw, boolean required) {
        String state = raw == null || raw.isBlank() ? (required ? "REQUIRED" : "NONE")
                : raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("UNKNOWN", "NONE", "REQUIRED").contains(state)) {
            throw new IllegalArgumentException("HTTP API authentication state is invalid: " + state);
        }
        if (required != "REQUIRED".equals(state)) {
            throw new IllegalArgumentException("HTTP API authentication required flag conflicts with state");
        }
        return state;
    }

    private List<String> normalizedMediaTypes(List<String> values) {
        return normalizedText(values, "content type", true);
    }

    private List<String> normalizedText(Collection<String> values, String field, boolean lowerCase) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> text(value, field))
                .map(value -> lowerCase ? value.toLowerCase(Locale.ROOT) : value)
                .distinct()
                .sorted()
                .toList();
    }

    private List<CanonicalMappingCondition> normalizedConditions(
            List<HttpApiOperationContract.MappingCondition> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<CanonicalMappingCondition> unique = new LinkedHashSet<>();
        for (HttpApiOperationContract.MappingCondition condition : values) {
            if (condition == null || condition.kind() == null || condition.operator() == null) {
                throw new IllegalArgumentException("HTTP API mapping condition kind and operator are required");
            }
            boolean header = condition.kind() == HttpApiMappingConditionKind.HEADER;
            String name = header ? headerName(condition.name())
                    : identifier(condition.name(), "mapping parameter");
            String operator = condition.operator().name();
            boolean requiresValue = condition.operator() == HttpApiMappingConditionOperator.EQUALS
                    || condition.operator() == HttpApiMappingConditionOperator.NOT_EQUALS;
            String value = normalizedConditionValue(condition.value(), requiresValue);
            if (sensitiveName(name) && requiresValue) {
                throw new IllegalArgumentException("HTTP API sensitive mapping conditions allow only presence predicates");
            }
            if (requiresValue && SECRET_ASSIGNMENT.matcher(value).find()) {
                throw new IllegalArgumentException("HTTP API mapping conditions cannot contain credential material");
            }
            CanonicalMappingCondition canonical = new CanonicalMappingCondition(
                    condition.kind().name(), name, operator, value);
            unique.add(canonical);
        }
        return unique.stream()
                .sorted(Comparator.comparing(CanonicalMappingCondition::kind)
                        .thenComparing(CanonicalMappingCondition::name)
                        .thenComparing(CanonicalMappingCondition::operator)
                        .thenComparing(CanonicalMappingCondition::value,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    private String normalizedConditionValue(String raw, boolean required) {
        if (!required) {
            if (raw != null && !raw.isBlank()) {
                throw new IllegalArgumentException("HTTP API presence mapping conditions cannot carry a value");
            }
            return null;
        }
        if (raw == null) {
            throw new IllegalArgumentException("HTTP API equality mapping condition value is required");
        }
        String value = raw.trim();
        if (value.length() > 1024 || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("HTTP API mapping condition value is invalid");
        }
        return value;
    }

    private JsonNode schema(JsonNode raw, boolean sensitiveContext) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        return canonicalTree(sanitizeSchema(raw, sensitiveContext));
    }

    private JsonNode sanitizeSchema(JsonNode raw, boolean sensitiveContext) {
        if (raw == null || raw.isNull() || raw.isValueNode()) {
            return raw == null ? JsonNodeFactory.instance.nullNode() : raw.deepCopy();
        }
        if (raw.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            raw.forEach(item -> result.add(sanitizeSchema(item, sensitiveContext)));
            return result;
        }

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var fields = raw.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (SAMPLE_SCHEMA_KEYWORDS.contains(normalizedKey)) {
                continue;
            }
            if (sensitiveContext && ("enum".equals(normalizedKey) || "const".equals(normalizedKey))) {
                throw new IllegalArgumentException("HTTP API sensitive schema cannot retain enum or const values");
            }
            if (schemaMapKeyword(normalizedKey) && field.getValue().isObject()) {
                result.set(key, sanitizeSchemaMap(field.getValue(), sensitiveContext,
                        schemaMapKeysRepresentBusinessFields(normalizedKey)));
            } else {
                result.set(key, sanitizeSchema(field.getValue(), sensitiveContext));
            }
        }
        return result;
    }

    private JsonNode sanitizeSchemaMap(JsonNode raw,
                                       boolean sensitiveContext,
                                       boolean keysRepresentBusinessFields) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        raw.fields().forEachRemaining(entry -> result.set(entry.getKey(),
                sanitizeSchema(entry.getValue(), sensitiveContext
                        || (keysRepresentBusinessFields && sensitiveName(entry.getKey())))));
        return result;
    }

    private boolean schemaMapKeyword(String key) {
        return "properties".equals(key) || "patternproperties".equals(key) || "dependentschemas".equals(key)
                || "definitions".equals(key) || "$defs".equals(key);
    }

    /** Definitions are type names; only field-oriented schema maps extend sensitive context. */
    private boolean schemaMapKeysRepresentBusinessFields(String key) {
        return "properties".equals(key) || "patternproperties".equals(key)
                || "dependentschemas".equals(key);
    }

    private String httpMethod(String raw) {
        String value = text(raw, "HTTP method").toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(value)) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + value);
        }
        return value;
    }

    private String sideEffect(String raw) {
        String value = text(raw, "side effect").toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SIDE_EFFECTS.contains(value)) {
            throw new IllegalArgumentException("Unsupported HTTP API side effect: " + value);
        }
        return value;
    }

    private String normalizeContextPath(String raw) {
        if (raw == null || raw.isBlank() || "/".equals(raw.trim())) {
            return "";
        }
        return normalizePath(raw, "context path");
    }

    private String normalizeEndpointPath(String raw) {
        return normalizePath(raw, "endpoint path");
    }

    private String normalizePath(String raw, String field) {
        String value = text(raw, field).replaceAll("/+", "/");
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() > 1024 || value.contains("?") || value.contains("#")) {
            throw new IllegalArgumentException("HTTP API " + field + " is invalid");
        }
        return value;
    }

    private String joinRoute(String contextPath, String endpointPath) {
        String joined = (contextPath + "/" + endpointPath).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private String responseStatus(String raw) {
        String value = text(raw, "response status").toUpperCase(Locale.ROOT);
        if (!value.matches("(?:[1-5][0-9]{2}|[1-5]XX|DEFAULT)")) {
            throw new IllegalArgumentException("HTTP API response status is invalid: " + value);
        }
        return value;
    }

    private String identifier(String raw, String field) {
        String value = text(raw, field);
        if (value.length() > 256 || value.contains("=") || value.contains(":") || value.contains("\r")
                || value.contains("\n")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private String headerName(String raw) {
        String value = identifier(raw, "HTTP header name").toLowerCase(Locale.ROOT);
        if (!value.matches("[!#$%&'*+.^_|~0-9a-z-]+")) {
            throw new IllegalArgumentException("HTTP header name is invalid");
        }
        return value;
    }

    private boolean sensitiveName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("authorization") || normalized.equals("cookie")
                || normalized.equals("set-cookie") || normalized.contains("api-key")
                || normalized.contains("apikey") || normalized.contains("token")
                || normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("credential");
    }

    private String text(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return raw.trim();
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(canonicalTree(objectMapper.valueToTree(value)));
        } catch (Exception failure) {
            throw new IllegalArgumentException("HTTP API contract cannot be canonicalized", failure);
        }
    }

    private JsonNode canonicalTree(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isValueNode()) {
            return raw == null ? JsonNodeFactory.instance.nullNode() : raw.deepCopy();
        }
        if (raw.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            raw.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> result.set(key, canonicalTree(value)));
            return result;
        }
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        // Arrays in JSON Schema can carry positional meaning (for example prefixItems), so only
        // explicitly set-like contract collections are sorted by their normalizers above.
        raw.forEach(value -> result.add(canonicalTree(value)));
        return result;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    public record CanonicalHttpApiContract(
            HttpApiServiceScope scope,
            String identityHash,
            String qualifiedName,
            String httpMethod,
            String routeTemplate,
            String mappingConditionsJson,
            String contractHash,
            String contractJson
    ) {
    }

    private record ScopedIdentity(HttpApiServiceScope.StableScope scope, CanonicalIdentity identity) {
    }

    private record CanonicalContract(
            HttpApiServiceScope.StableScope scope,
            CanonicalIdentity identity,
            List<CanonicalParameter> parameters,
            CanonicalRequestBody requestBody,
            List<CanonicalResponse> responses,
            CanonicalAuthentication authentication,
            String sideEffect
    ) {
    }

    private record CanonicalIdentity(
            String method,
            String routeTemplate,
            CanonicalMappingConditions mappingConditions
    ) {
    }

    private record CanonicalMappingConditions(
            List<String> consumes,
            List<String> produces,
            List<CanonicalMappingCondition> conditions
    ) {
    }

    private record CanonicalMappingCondition(
            String kind,
            String name,
            String operator,
            String value
    ) {
    }

    private record CanonicalParameter(
            String name,
            String location,
            boolean required,
            JsonNode schema,
            List<String> contentTypes
    ) {
    }

    private record CanonicalRequestBody(
            boolean required,
            JsonNode schema,
            List<String> contentTypes
    ) {
    }

    private record CanonicalResponse(
            String status,
            JsonNode schema,
            List<String> contentTypes
    ) {
    }

    private record CanonicalAuthentication(
            String state,
            List<String> schemes,
            List<String> requiredHeaderNames
    ) {
    }
}
