package com.enterprise.ai.agent.tools.dynamic;

import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.schema.LlmJsonSchemaProvider;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import com.enterprise.ai.runtime.contract.AiTool;
import com.enterprise.ai.runtime.contract.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

@Slf4j
public class DynamicHttpAiTool implements AiTool, LlmJsonSchemaProvider {

    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 180_000L;
    public static final long MIN_REQUEST_TIMEOUT_MS = 1_000L;
    public static final long MAX_REQUEST_TIMEOUT_MS = 1_800_000L;

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String REACHAI_CAPABILITY_INVOKE_PROTOCOL = "REACHAI_CAPABILITY_HTTP";

    /**
     * 每次调用附加的 HTTP 头与查询参数（例如扫描项目级 API Key）。
     */
    public record HttpInvocationExtras(Map<String, String> extraHeaders, Map<String, String> extraQueryParams) {
        public static final HttpInvocationExtras EMPTY = new HttpInvocationExtras(Map.of(), Map.of());

        public HttpInvocationExtras {
            extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
            extraQueryParams = extraQueryParams == null ? Map.of() : Map.copyOf(extraQueryParams);
        }

        public boolean isEmpty() {
            return extraHeaders.isEmpty() && extraQueryParams.isEmpty();
        }
    }

    private final ToolDefinitionEntity definition;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final List<ToolDefinitionParameter> parameters;
    private final HttpInvocationExtras invocationExtras;
    private final Duration requestTimeout;
    private final InvocationTokenProvider invocationTokenProvider;

    public interface InvocationTokenProvider {
        String createToken(ToolDefinitionEntity definition, ToolExecutionContext context);
    }

    public DynamicHttpAiTool(ToolDefinitionEntity definition, ObjectMapper objectMapper) {
        this(definition, objectMapper, null, null);
    }

    public DynamicHttpAiTool(ToolDefinitionEntity definition, ObjectMapper objectMapper,
                             HttpInvocationExtras invocationExtras) {
        this(definition, objectMapper, invocationExtras, null);
    }

    public DynamicHttpAiTool(ToolDefinitionEntity definition, ObjectMapper objectMapper,
                             HttpInvocationExtras invocationExtras,
                             Duration requestTimeout) {
        this(definition, objectMapper, invocationExtras, requestTimeout, null);
    }

    public DynamicHttpAiTool(ToolDefinitionEntity definition, ObjectMapper objectMapper,
                             HttpInvocationExtras invocationExtras,
                             Duration requestTimeout,
                             InvocationTokenProvider invocationTokenProvider) {
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.requestTimeout = normalizeRequestTimeout(requestTimeout);
        String baseUrl = DynamicHttpToolBaseUrlSupport.normalizeHttpBaseUrl(definition.getBaseUrl());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory(this.requestTimeout))
                .build();
        this.parameters = parseParameters(definition.getParametersJson());
        this.invocationExtras = invocationExtras == null || invocationExtras.isEmpty()
                ? HttpInvocationExtras.EMPTY
                : invocationExtras;
        this.invocationTokenProvider = invocationTokenProvider;
    }

    @Override
    public String name() {
        return definition.getName();
    }

    @Override
    public String description() {
        String aiDescription = definition.getAiDescription();
        if (aiDescription != null && !aiDescription.isBlank()) {
            return aiDescription;
        }
        return definition.getDescription();
    }

    @Override
    public List<ToolParameter> parameters() {
        return parameters.stream()
                .map(parameter -> new ToolParameter(
                        parameter.name(),
                        parameter.type(),
                        parameter.description(),
                        parameter.required()
                ))
                .toList();
    }

    @Override
    public Map<String, Object> llmParametersJsonSchema() {
        return buildRootParametersSchema(parameters);
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        Map<String, Object> pathVariables = new LinkedHashMap<>();
        Map<String, Object> queryParameters = new LinkedHashMap<>();
        Object body = null;

        for (ToolDefinitionParameter parameter : parameters) {
            Object value = safeArgs.get(parameter.name());
            if ("PATH".equalsIgnoreCase(parameter.location())) {
                pathVariables.put(parameter.name(), value);
            } else if ("QUERY".equalsIgnoreCase(parameter.location())) {
                queryParameters.put(parameter.name(), value);
            } else if ("BODY".equalsIgnoreCase(parameter.location())) {
                body = value;
            }
        }
        for (Map.Entry<String, String> entry : invocationExtras.extraQueryParams().entrySet()) {
            queryParameters.put(entry.getKey(), entry.getValue());
        }

        String uri = Objects.requireNonNull(buildUri(pathVariables, queryParameters));
        String methodName = definition.getHttpMethod();
        if (methodName == null || methodName.isBlank()) {
            methodName = "GET";
        }
        HttpMethod httpMethod = HttpMethod.valueOf(methodName);
        Object requestBody = Objects.requireNonNull(shouldSendBody(httpMethod)
                ? resolveRequestBody(safeArgs, body)
                : Map.of());

        try {
            var spec = restClient.method(httpMethod).uri(uri);
            Map<String, String> headers = invocationHeaders();
            if (!headers.isEmpty()) {
                spec = spec.headers(httpHeaders ->
                        headers.forEach((name, value) -> httpHeaders.set(name, value == null ? "" : value)));
            }
            return spec.body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            log.error("[DynamicHttpAiTool] 调用失败: name={}, uri={}", definition.getName(), uri, ex);
            throw new IllegalStateException("Dynamic tool invocation failed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, String> invocationHeaders() {
        ToolExecutionContext context = ToolExecutionContextHolder.get();
        Map<String, String> headers = new LinkedHashMap<>(contextHeaders(context));
        headers.putAll(invocationExtras.extraHeaders());
        String invocationToken = invocationToken(context);
        if (invocationToken != null && !invocationToken.isBlank()) {
            headers.put(ReachAiInvocationToken.HEADER_NAME, invocationToken);
        }
        return headers;
    }

    private String invocationToken(ToolExecutionContext context) {
        if (!isReachAiCapabilityHostProtocol() || invocationTokenProvider == null || context == null) {
            return null;
        }
        return invocationTokenProvider.createToken(definition, context);
    }

    private Map<String, String> contextHeaders(ToolExecutionContext context) {
        if (context == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        putContextHeader(headers, "Project-Code", context.getProjectCode());
        putContextHeader(headers, "Agent-Id", firstNonBlank(context.getAgentId(), context.getAgentName()));
        putContextHeader(headers, "Trace-Id", context.getTraceId());
        putContextHeader(headers, "Session-Id", context.getSessionId());
        putContextHeader(headers, "User-Id", firstNonBlank(context.getExternalUserId(), context.getUserId()));
        putContextHeader(headers, "Global-User-Id", context.getGlobalUserId());
        putContextHeader(headers, "Roles", joinRoles(context.getRoles()));
        putContextHeader(headers, "Tenant-Id", context.getTenantId());
        putContextHeader(headers, "App-Id", context.getAppId());
        putContextHeader(headers, "Page-Instance-Id", context.getPageInstanceId());
        return headers;
    }

    private void putContextHeader(Map<String, String> headers, String suffix, String value) {
        putHeader(headers, "X-ReachAI-" + suffix, value);
        putHeader(headers, "X-EAF-" + suffix, value);
    }

    private void putHeader(Map<String, String> headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.put(name, value);
        }
    }

    private String joinRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner(",");
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                joiner.add(role.trim());
            }
        }
        return joiner.length() == 0 ? null : joiner.toString();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private boolean shouldSendBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }

    private SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        return factory;
    }

    public static Duration normalizeRequestTimeout(Duration timeout) {
        long millis = timeout == null ? DEFAULT_REQUEST_TIMEOUT_MS : timeout.toMillis();
        millis = Math.max(MIN_REQUEST_TIMEOUT_MS, Math.min(MAX_REQUEST_TIMEOUT_MS, millis));
        return Duration.ofMillis(millis);
    }

    private String buildUri(Map<String, Object> pathVariables, Map<String, Object> queryParameters) {
        String resolvedPath = joinPath(definition.getContextPath(), definition.getEndpointPath());
        for (Map.Entry<String, Object> entry : pathVariables.entrySet()) {
            resolvedPath = resolvedPath.replace("{" + entry.getKey() + "}", Objects.toString(entry.getValue(), ""));
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(Objects.requireNonNull(resolvedPath));
        for (Map.Entry<String, Object> entry : queryParameters.entrySet()) {
            Object queryValue = entry.getValue();
            if (queryValue != null) {
                builder.queryParam(Objects.requireNonNull(entry.getKey()), queryValue);
            }
        }
        return builder.build(false).toUriString();
    }

    private Object normalizeBody(Object body) {
        if (body instanceof String bodyJson) {
            if (bodyJson.isBlank()) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(bodyJson, Object.class);
            } catch (Exception ex) {
                throw new IllegalArgumentException("body_json is not valid JSON", ex);
            }
        }
        return body == null ? Map.of() : body;
    }

    private Object resolveRequestBody(Map<String, Object> safeArgs, Object body) {
        if (isReachAiCapabilityHostProtocol()) {
            return safeArgs;
        }
        return normalizeBody(body);
    }

    private boolean isReachAiCapabilityHostProtocol() {
        String metadataJson = definition.getCapabilityMetadataJson();
        if (metadataJson == null || metadataJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, MAP_TYPE);
            Object invokeProtocol = metadata.get("invokeProtocol");
            return invokeProtocol != null
                    && REACHAI_CAPABILITY_INVOKE_PROTOCOL.equalsIgnoreCase(invokeProtocol.toString());
        } catch (Exception ex) {
            log.warn("[DynamicHttpAiTool] capability metadata parse failed: name={}", definition.getName(), ex);
            return false;
        }
    }

    private List<ToolDefinitionParameter> parseParameters(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(parametersJson, PARAMETER_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse tool parameters JSON", ex);
        }
    }

    /**
     * 将带 {@link ToolDefinitionParameter#children()} 的树展开为 OpenAI 风格 JSON Schema（根为 object）。
     */
    static Map<String, Object> buildRootParametersSchema(List<ToolDefinitionParameter> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (params != null) {
            for (ToolDefinitionParameter p : params) {
                properties.put(p.name(), parameterToPropertySchema(p));
                if (p.required()) {
                    required.add(p.name());
                }
            }
        }
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> parameterToPropertySchema(ToolDefinitionParameter p) {
        Map<String, Object> prop = new LinkedHashMap<>();
        String jsonType = normalizeJsonSchemaType(p.type());
        prop.put("type", jsonType);
        if (p.description() != null && !p.description().isBlank()) {
            prop.put("description", p.description());
        }
        List<ToolDefinitionParameter> children = p.children();
        if (children == null || children.isEmpty()) {
            return prop;
        }
        if ("object".equals(jsonType)) {
            Map<String, Object> nestedProps = new LinkedHashMap<>();
            List<String> nestedRequired = new ArrayList<>();
            for (ToolDefinitionParameter c : children) {
                nestedProps.put(c.name(), parameterToPropertySchema(c));
                if (c.required()) {
                    nestedRequired.add(c.name());
                }
            }
            prop.put("properties", nestedProps);
            if (!nestedRequired.isEmpty()) {
                prop.put("required", nestedRequired);
            }
        } else if ("array".equals(jsonType)) {
            ToolDefinitionParameter item = children.get(0);
            prop.put("items", parameterToPropertySchema(item));
        }
        return prop;
    }

    private static String normalizeJsonSchemaType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "string";
        }
        String t = rawType.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "integer", "int", "long" -> "integer";
            case "number", "float", "double" -> "number";
            case "boolean", "bool" -> "boolean";
            case "object", "json" -> "object";
            case "array", "list" -> "array";
            default -> "string";
        };
    }

    private String joinPath(String left, String right) {
        String normalizedLeft = left == null ? "" : left.trim();
        String normalizedRight = right == null ? "" : right.trim();

        if (normalizedLeft.isEmpty()) {
            return normalizedRight.startsWith("/") ? normalizedRight : "/" + normalizedRight;
        }
        if (normalizedRight.isEmpty()) {
            return normalizedLeft.startsWith("/") ? normalizedLeft : "/" + normalizedLeft;
        }
        String leftPart = normalizedLeft.endsWith("/") ? normalizedLeft.substring(0, normalizedLeft.length() - 1) : normalizedLeft;
        String rightPart = normalizedRight.startsWith("/") ? normalizedRight : "/" + normalizedRight;
        return leftPart + rightPart;
    }
}
