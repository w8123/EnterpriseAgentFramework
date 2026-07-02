package com.enterprise.ai.control.compat;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Public /api capability route bridge from Control to the owning Capability service.
 * Direction: frontend -> reachai-control-service -> reachai-capability-service.
 */
@RestController
public class CapabilityCompatibilityProxyController {

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            HttpHeaders.CONNECTION,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.HOST,
            "Keep-Alive",
            "Proxy-Authenticate",
            "Proxy-Authorization",
            "TE",
            "Trailer",
            "Transfer-Encoding",
            "Upgrade"
    );

    private final RestTemplate restTemplate;
    private final String capabilityServiceUrl;

    @Autowired
    public CapabilityCompatibilityProxyController(RestTemplateBuilder restTemplateBuilder,
                                                  @Value("${services.capability-service.url:http://localhost:18605}")
                                                  String capabilityServiceUrl) {
        this(restTemplateBuilder.build(), capabilityServiceUrl);
    }

    CapabilityCompatibilityProxyController(RestTemplate restTemplate, String capabilityServiceUrl) {
        this.restTemplate = restTemplate;
        this.capabilityServiceUrl = normalizeBaseUrl(capabilityServiceUrl);
    }

    @RequestMapping(path = {
            "/api/registry/{*path}",
            "/api/capabilities",
            "/api/capabilities/{*path}",
            "/api/tools",
            "/api/tools/{*path}",
            "/api/compositions",
            "/api/compositions/{*path}",
            "/api/api-assets",
            "/api/api-assets/{*path}",
            "/api/api-graph",
            "/api/api-graph/{*path}",
            "/api/tool-retrieval",
            "/api/tool-retrieval/{*path}",
            "/api/skill-mining",
            "/api/skill-mining/{*path}",
            "/api/capability-mining",
            "/api/capability-mining/{*path}",
            "/api/scan-projects",
            "/api/scan-projects/{*path}",
            "/api/scan-modules",
            "/api/scan-modules/{*path}",
            "/api/semantic-docs",
            "/api/semantic-docs/{*path}",
            "/api/domains",
            "/api/domains/{*path}"
    })
    public ResponseEntity<byte[]> proxy(RequestEntity<byte[]> requestEntity, HttpServletRequest request) {
        URI targetUri = targetUri(request);
        HttpEntity<byte[]> entity = new HttpEntity<>(requestEntity.getBody(), forwardHeaders(requestEntity.getHeaders()));
        try {
            return restTemplate.exchange(targetUri, requestEntity.getMethod(), entity, byte[].class);
        } catch (RestClientResponseException ex) {
            return ResponseEntity.status(ex.getStatusCode())
                    .headers(forwardHeaders(ex.getResponseHeaders()))
                    .body(ex.getResponseBodyAsByteArray());
        } catch (ResourceAccessException ex) {
            return unavailable();
        }
    }

    private URI targetUri(HttpServletRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(capabilityServiceUrl)
                .path(request.getRequestURI());
        if (StringUtils.hasText(request.getQueryString())) {
            builder.query(request.getQueryString());
        }
        return builder.build(true).toUri();
    }

    private HttpHeaders forwardHeaders(HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        if (source != null) {
            source.forEach((name, values) -> {
                if (!isHopByHopHeader(name)) {
                    headers.put(name, values);
                }
            });
        }
        headers.set("X-ReachAI-Control-Capability-Proxy", "true");
        return headers;
    }

    private boolean isHopByHopHeader(String name) {
        return HOP_BY_HOP_HEADERS.stream().anyMatch(header -> header.equalsIgnoreCase(name));
    }

    private String normalizeBaseUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!StringUtils.hasText(trimmed)) {
            return "http://localhost:18605";
        }
        return trimmed.replaceAll("/+$", "");
    }

    private ResponseEntity<byte[]> unavailable() {
        String body = "{\"code\":\"CAPABILITY_SERVICE_UNAVAILABLE\","
                + "\"message\":\"ReachAI Capability Service is unavailable\"}";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
