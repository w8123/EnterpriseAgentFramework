package com.enterprise.ai.agent.tools.dynamic;

import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class DynamicHttpAiTool implements AiTool {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };

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

    public DynamicHttpAiTool(ToolDefinitionEntity definition, ObjectMapper objectMapper) {
        this(definition, objectMapper, null);
    }

    public DynamicHttpAiTool(ToolDefinitionEntity definition, ObjectMapper objectMapper,
                             HttpInvocationExtras invocationExtras) {
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        String baseUrl = definition.getBaseUrl();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl == null ? "" : baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.parameters = parseParameters(definition.getParametersJson());
        this.invocationExtras = invocationExtras == null || invocationExtras.isEmpty()
                ? HttpInvocationExtras.EMPTY
                : invocationExtras;
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
        Object requestBody = Objects.requireNonNull(shouldSendBody(httpMethod) ? normalizeBody(body) : Map.of());

        try {
            var spec = restClient.method(httpMethod).uri(uri);
            if (!invocationExtras.extraHeaders().isEmpty()) {
                spec = spec.headers(headers -> invocationExtras.extraHeaders()
                        .forEach((name, value) -> headers.add(name, value == null ? "" : value)));
            }
            return spec.body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            log.error("[DynamicHttpAiTool] 调用失败: name={}, uri={}", definition.getName(), uri, ex);
            throw new IllegalStateException("Dynamic tool invocation failed: " + ex.getMessage(), ex);
        }
    }

    private boolean shouldSendBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
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
