package ${basePackage};

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class ${clientClassName} {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ${clientClassName}(@Value("<#noparse>${</#noparse>${configPrefix}:${baseUrl}}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String invoke(
            String method,
            String pathTemplate,
            Map<String, Object> pathVariables,
            Map<String, Object> queryParameters,
            Object body
    ) {
        String uri = buildUri(pathTemplate, pathVariables, queryParameters);
        try {
            return switch (method) {
                case "GET" -> restClient.get()
                        .uri(uri)
                        .retrieve()
                        .body(String.class);
                case "DELETE" -> restClient.delete()
                        .uri(uri)
                        .retrieve()
                        .body(String.class);
                case "PUT" -> restClient.put()
                        .uri(uri)
                        .body(normalizeBody(body))
                        .retrieve()
                        .body(String.class);
                case "PATCH" -> restClient.patch()
                        .uri(uri)
                        .body(normalizeBody(body))
                        .retrieve()
                        .body(String.class);
                case "POST" -> restClient.post()
                        .uri(uri)
                        .body(normalizeBody(body))
                        .retrieve()
                        .body(String.class);
                default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            };
        } catch (Exception ex) {
            log.error("Failed to invoke generated skill endpoint: method={}, uri={}", method, uri, ex);
            return "{\"error\":\"Generated skill invocation failed: " + ex.getMessage() + "\"}";
        }
    }

    private String buildUri(String pathTemplate, Map<String, Object> pathVariables, Map<String, Object> queryParameters) {
        String resolvedPath = "${contextPath}" + pathTemplate;
        for (Map.Entry<String, Object> entry : pathVariables.entrySet()) {
            resolvedPath = resolvedPath.replace("{" + entry.getKey() + "}", Objects.toString(entry.getValue(), ""));
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(resolvedPath);
        for (Map.Entry<String, Object> entry : queryParameters.entrySet()) {
            if (entry.getValue() != null) {
                builder.queryParam(entry.getKey(), entry.getValue());
            }
        }
        return builder.build(true).toUriString();
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
}
