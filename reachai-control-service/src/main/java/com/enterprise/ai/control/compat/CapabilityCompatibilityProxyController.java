package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

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

    private static final Pattern PROJECT_DETAIL_PATH = Pattern.compile("/api/scan-projects/\\d+");
    private static final List<String> PROJECT_SUMMARY_FIELDS = List.of(
            "id", "name", "projectCode", "projectKind", "environment", "status", "description");

    private final ObjectMapper objectMapper = new ObjectMapper();
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

    @RequestMapping(path = "/api/registry/{*path}")
    public ResponseEntity<byte[]> proxyRegistry(RequestEntity<byte[]> requestEntity, HttpServletRequest request) {
        if (isRetiredRegistryManagementPath(request.getRequestURI())) {
            return retiredRegistryManagementRoute();
        }
        // SDK registry calls have explicit, signed handlers. The generic bridge
        // only carries console instance operations guarded by the platform session.
        if (!isConsoleInstancePath(request.getRequestURI())) {
            return unknownRegistryRoute();
        }
        return proxyRequest(requestEntity, request);
    }

    @RequestMapping(path = {
            "/api/capabilities",
            "/api/capabilities/{*path}"
    })
    public ResponseEntity<byte[]> retiredCapabilities() {
        return retiredCapabilityKernelRoute();
    }

    /**
     * Console-only Capability catalog APIs. These paths are guarded by
     * PlatformConsoleAuthInterceptor before this proxy is entered.
     */
    @RequestMapping(path = {
            "/api/scan-projects",
            "/api/scan-projects/{*path}"
    })
    public ResponseEntity<byte[]> proxy(RequestEntity<byte[]> requestEntity, HttpServletRequest request) {
        ResponseEntity<byte[]> response = proxyRequest(requestEntity, request);
        if (!HttpMethod.GET.equals(requestEntity.getMethod()) || !response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        String path = request.getRequestURI();
        if ("/api/scan-projects".equals(path)) {
            return sanitizeProjects(response, canReadProjectDetails(request));
        }
        if (PROJECT_DETAIL_PATH.matcher(path).matches()) {
            return sanitizeProjects(response, true);
        }
        return response;
    }

    /**
     * The console never needs a project's stored API key value, and callers who only pick
     * projects (managers, operators of a single area) get the identity fields alone.
     */
    private ResponseEntity<byte[]> sanitizeProjects(ResponseEntity<byte[]> response, boolean details) {
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root == null) return response;
            if (root.isArray()) {
                ArrayNode rows = objectMapper.createArrayNode();
                root.forEach(row -> rows.add(sanitizeProject(row, details)));
                root = rows;
            } else {
                root = sanitizeProject(root, details);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(response.getHeaders());
            headers.remove(HttpHeaders.CONTENT_LENGTH);
            return ResponseEntity.status(response.getStatusCode()).headers(headers)
                    .body(objectMapper.writeValueAsBytes(root));
        } catch (IOException ex) {
            return unavailable();
        }
    }

    private JsonNode sanitizeProject(JsonNode row, boolean details) {
        if (!(row instanceof ObjectNode project)) return row;
        if (!details) {
            ObjectNode summary = objectMapper.createObjectNode();
            PROJECT_SUMMARY_FIELDS.forEach(field -> {
                if (project.has(field)) summary.set(field, project.get(field));
            });
            return summary;
        }
        project.remove("authApiKeyValue");
        return project;
    }

    private boolean canReadProjectDetails(HttpServletRequest request) {
        return request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE)
                instanceof PlatformAuthenticatedSession session
                && (session.permissions().contains(PlatformPermissions.PLATFORM_READ)
                || session.permissions().contains(PlatformPermissions.ALL));
    }

    private ResponseEntity<byte[]> proxyRequest(RequestEntity<byte[]> requestEntity, HttpServletRequest request) {
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

    private boolean isRetiredRegistryManagementPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return path.matches("^/api/registry/projects/[^/]+/capabilities/(diff|apply)$")
                || path.matches("^/api/registry/projects/[^/]+/capability-snapshots$")
                || path.matches("^/api/registry/projects/[^/]+/capability-snapshots/[0-9]+/diff-items$")
                || path.matches("^/api/registry/projects/[^/]+/capability-diff-items/[0-9]+/(review|rollback)$")
                || path.matches("^/api/registry/capability-snapshots/[0-9]+/diff-items$")
                || path.matches("^/api/registry/capability-diff-items/[0-9]+/(review|rollback)$");
    }

    private boolean isConsoleInstancePath(String path) {
        return StringUtils.hasText(path)
                && path.matches("^/api/registry/projects/[^/]+/instances/(status|purge-offline)$");
    }

    private ResponseEntity<byte[]> unknownRegistryRoute() {
        String body = "{\"code\":\"REGISTRY_ROUTE_NOT_FOUND\","
                + "\"message\":\"Unknown registry route\"}";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> retiredCapabilityKernelRoute() {
        String body = "{\"code\":\"CAPABILITY_KERNEL_ROUTE_RETIRED\","
                + "\"message\":\"The public /api/capabilities product entry has been retired\"}";
        return ResponseEntity.status(HttpStatus.GONE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> retiredRegistryManagementRoute() {
        String body = "{\"code\":\"CAPABILITY_REVIEW_ROUTE_RETIRED\","
                + "\"message\":\"Use the platform-authenticated /api/capability-review routes\"}";
        return ResponseEntity.status(HttpStatus.GONE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
