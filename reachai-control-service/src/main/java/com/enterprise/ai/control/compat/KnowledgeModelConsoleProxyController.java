package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Console routes of the Knowledge and Model services.
 *
 * <p>The admin console used to reach these services directly through Nginx,
 * which left knowledge bases and model calls open to anyone. Control now owns
 * the public paths: PlatformConsoleAuthInterceptor attests the session and the
 * route permission before a request is forwarded to the owning service.
 * Only the paths the console uses are bridged; owner-internal routes stay
 * unreachable from the browser.</p>
 */
@RestController
public class KnowledgeModelConsoleProxyController {

    static final String MODEL_STREAM_PATH = "/model/chat/stream/events";
    private static final java.util.regex.Pattern MODEL_WRITE_PATH = java.util.regex.Pattern.compile("/model/instances/(?!(?:provider-accounts|test-draft|discover-models|from-template)(?:/|$))([^/]+)(/.*)?");
    private static final java.util.regex.Pattern MODEL_PLATFORM_WRITE_PATH = java.util.regex.Pattern.compile(
            "/model/catalog/.*|/model/instances/provider-accounts(?!/discover$)(/.*)?");
    private static final java.util.regex.Pattern MODEL_DRAFT_PATH = java.util.regex.Pattern.compile("/model/instances/(?:test-draft|discover-models)");
    private static final java.util.regex.Pattern MODEL_CONNECTION_TEST_PATH = java.util.regex.Pattern.compile("/model/instances/[^/]+/test-connection-model");
    private static final java.util.regex.Pattern MODEL_CONNECTION_CREATE_PATH = java.util.regex.Pattern.compile("/model/instances/[^/]+/connection-models");
    private static final java.util.regex.Pattern MODEL_FROM_TEMPLATE_PATH = java.util.regex.Pattern.compile("/model/instances/from-template/[^/]+");
    private static final java.util.regex.Pattern KNOWLEDGE_BASE_PATH = java.util.regex.Pattern.compile("/ai/knowledge/base/([^/]+)");
    private static final java.util.regex.Pattern KNOWLEDGE_FILE_PATH = java.util.regex.Pattern.compile("/ai/file/([^/]+)");
    private static final java.util.Set<String> RETRIEVAL_FIELDS = java.util.Set.of("query", "knowledgeBaseCodes", "topK", "scoreThreshold",
            "searchMode", "rerankEnabled", "vectorWeight", "keywordWeight", "recordHit");
    private static final java.util.regex.Pattern CHUNK_WRITE_PATH = java.util.regex.Pattern.compile("/ai/knowledge/chunks/(\\d+)(/toggle|/reembed)?");

    /** Headers that must not cross the boundary: hop-by-hop plus the console's own credentials. */
    private static final List<String> DROPPED_HEADERS = List.of(
            HttpHeaders.CONNECTION,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.HOST,
            HttpHeaders.COOKIE,
            HttpHeaders.AUTHORIZATION,
            "X-ReachAI-CSRF",
            "Keep-Alive",
            "Proxy-Authenticate",
            "Proxy-Authorization",
            "TE",
            "Trailer",
            "Transfer-Encoding",
            "Upgrade"
    );

    private final RestTemplate restTemplate;
    private final HttpClient streamClient;
    private McpPlatformKnowledgeCatalogPort chunkScope;
    private com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort modelScope;
    private final String knowledgeServiceUrl;
    private final String modelServiceUrl;
    private String modelServiceSecret = "";

    @Autowired
    public void configureModelServiceSecret(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String secret) {
        this.modelServiceSecret = secret;
    }

    @Autowired
    public KnowledgeModelConsoleProxyController(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${services.knowledge-service.url:http://localhost:18602}") String knowledgeServiceUrl,
            @Value("${services.model-service.url:http://localhost:18601}") String modelServiceUrl) {
        this(restTemplateBuilder.setReadTimeout(Duration.ofMinutes(10)).build(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                knowledgeServiceUrl, modelServiceUrl);
    }

    KnowledgeModelConsoleProxyController(RestTemplate restTemplate, HttpClient streamClient,
                                         String knowledgeServiceUrl, String modelServiceUrl) {
        this.restTemplate = restTemplate;
        this.streamClient = streamClient;
        this.knowledgeServiceUrl = normalizeBaseUrl(knowledgeServiceUrl, "http://localhost:18602");
        this.modelServiceUrl = normalizeBaseUrl(modelServiceUrl, "http://localhost:18601");
    }

    @RequestMapping(path = {
            "/ai/knowledge/{*path}",
            "/ai/file/{*path}",
            "/ai/retrieval/{*path}"
    })
    public ResponseEntity<byte[]> knowledge(RequestEntity<byte[]> requestEntity, HttpServletRequest request) {
        var chunkWrite = CHUNK_WRITE_PATH.matcher(request.getRequestURI());
        if (chunkWrite.matches() && !"GET".equalsIgnoreCase(request.getMethod())) {
            HttpStatus denied = chunkWriteDenied(request, Long.parseLong(chunkWrite.group(1)));
            if (denied != null) return ResponseEntity.status(denied).build();
        } else if (!"GET".equalsIgnoreCase(request.getMethod())) {
            HttpStatus denied = request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE) instanceof PlatformAuthenticatedSession session
                    ? knowledgeWriteDenied(session, request.getMethod(), request.getRequestURI(), requestEntity.getBody()) : HttpStatus.FORBIDDEN;
            if (denied != null) return ResponseEntity.status(denied).build();
        }
        return forward(knowledgeServiceUrl, requestEntity, request, "KNOWLEDGE_SERVICE_UNAVAILABLE");
    }

    /**
     * Model routes other than the scoped instance list/detail/create handled by
     * ControlModelInstanceController. Literal provider-account paths keep
     * {@code /model/instances/{id}} from capturing them.
     */
    @RequestMapping(path = {
            "/model/templates",
            "/model/templates/{*path}",
            "/model/catalog/{*path}",
            "/model/chat",
            "/model/instances/provider-accounts",
            "/model/instances/provider-accounts/{*path}",
            "/model/instances/{id}/{*action}",
            "/model/instances/{id}"
    })
    public ResponseEntity<byte[]> model(RequestEntity<byte[]> requestEntity, HttpServletRequest request) {
        if (MODEL_CONNECTION_TEST_PATH.matcher(request.getRequestURI()).matches()
                && (!"POST".equalsIgnoreCase(request.getMethod()) || !validConnectionModelTest(requestEntity.getBody()))) {
            return ResponseEntity.badRequest().build();
        }
        if (MODEL_CONNECTION_CREATE_PATH.matcher(request.getRequestURI()).matches()
                && (!"POST".equalsIgnoreCase(request.getMethod()) || !validConnectionModelCreate(requestEntity.getBody()))) {
            return ResponseEntity.badRequest().build();
        }
        var modelWrite = MODEL_WRITE_PATH.matcher(request.getRequestURI());
        if (modelWrite.matches() && !"GET".equalsIgnoreCase(request.getMethod())) {
            HttpStatus denied = modelWriteDenied(request, modelWrite.group(1), requestEntity.getBody());
            if (denied != null) return ResponseEntity.status(denied).build();
        }
        if (MODEL_PLATFORM_WRITE_PATH.matcher(request.getRequestURI()).matches() && !"GET".equalsIgnoreCase(request.getMethod())
                && !(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE) instanceof PlatformAuthenticatedSession manager
                    && canManageModels(manager, null))) {
            // Provider accounts and the catalog are platform level: their keys serve instances in every project.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (MODEL_DRAFT_PATH.matcher(request.getRequestURI()).matches() && "POST".equalsIgnoreCase(request.getMethod())) {
            // A draft naming a saved instance reuses its stored key, possibly against a new address, so it needs the same
            // model:manage on that instance's project as editing it.
            String savedId = draftInstanceId(requestEntity.getBody());
            if (savedId == null) return ResponseEntity.badRequest().build();
            if (!savedId.isEmpty()) {
                HttpStatus denied = modelWriteDenied(request, savedId, requestEntity.getBody());
                if (denied != null) return ResponseEntity.status(denied).build();
            }
        }
        if (MODEL_FROM_TEMPLATE_PATH.matcher(request.getRequestURI()).matches() && "POST".equalsIgnoreCase(request.getMethod())) {
            // Creating from a template lands in the requested project (or platform level), like the scoped create handler.
            HttpStatus denied = !(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE) instanceof PlatformAuthenticatedSession session)
                    ? HttpStatus.FORBIDDEN : requestedProjectDenied(session, requestEntity.getBody(), true);
            if (denied != null) return ResponseEntity.status(denied).build();
        }
        return forward(modelServiceUrl, requestEntity, request, "MODEL_SERVICE_UNAVAILABLE");
    }

    private static boolean validConnectionModelTest(byte[] body) {
        if (body == null || body.length == 0 || body.length > 4096) return false;
        try {
            var value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (value == null || !value.isObject() || value.size() != 2) return false;
            var type = value.get("modelType");
            var name = value.get("modelName");
            return type != null && type.isTextual() && java.util.Set.of("LLM", "EMBEDDING", "RERANKER").contains(type.asText())
                    && name != null && name.isTextual() && !name.asText().isBlank() && name.asText().length() <= 256;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    private static boolean validConnectionModelCreate(byte[] body) {
        if (body == null || body.length == 0 || body.length > 4096) return false;
        try {
            var value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (value == null || !value.isObject() || value.size() != 3) return false;
            var type = value.get("modelType");
            var name = value.get("modelName");
            var title = value.get("name");
            return type != null && type.isTextual() && java.util.Set.of("LLM", "EMBEDDING", "RERANKER").contains(type.asText())
                    && name != null && name.isTextual() && !name.asText().isBlank() && name.asText().length() <= 256
                    && title != null && title.isTextual() && !title.asText().isBlank() && title.asText().length() <= 128;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    @PostMapping(path = MODEL_STREAM_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> modelStream(RequestEntity<byte[]> requestEntity,
                                                             HttpServletResponse servletResponse) {
        byte[] body = requestEntity.getBody() == null ? new byte[0] : requestEntity.getBody();
        HttpRequest.Builder upstreamBuilder = HttpRequest.newBuilder()
                .uri(URI.create(modelServiceUrl + MODEL_STREAM_PATH))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        com.enterprise.ai.common.internalauth.ModelServiceRequestAuth.sign(modelServiceSecret,
                com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.CALLER_CONTROL,
                "POST", modelServiceUrl + MODEL_STREAM_PATH, body).forEach(upstreamBuilder::header);
        HttpRequest upstream = upstreamBuilder.build();
        servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        StreamingResponseBody stream = output -> {
            try {
                HttpResponse<InputStream> response = streamClient.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream input = response.body()) {
                    copyFlushing(input, output);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
    }

    /**
     * The knowledge service's chunk endpoints are not resource scoped, so a chunk write is admitted only when the
     * signed-in user holds knowledge write on the knowledge base that owns the named file and the chunk belongs to it.
     */
    private HttpStatus chunkWriteDenied(HttpServletRequest request, long chunkId) {
        String fileId = request.getParameter("fileId");
        if (fileId == null || fileId.isBlank()) return HttpStatus.BAD_REQUEST;
        if (!(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE) instanceof PlatformAuthenticatedSession session)
                || chunkScope == null) {
            return HttpStatus.FORBIDDEN;
        }
        ResponseEntity<?> scoped;
        try { scoped = chunkScope.fileChunksForWrite(session, fileId.trim()); }
        catch (org.springframework.web.server.ResponseStatusException refused) {
            return refused.getStatusCode().is4xxClientError() ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (scoped == null || !scoped.getStatusCode().is2xxSuccessful() || !(scoped.getBody() instanceof Map<?, ?> body)) {
            return scoped != null && scoped.getStatusCode().is4xxClientError() ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (body.get("chunks") instanceof com.fasterxml.jackson.databind.JsonNode chunks) {
            for (var chunk : chunks) if (chunk.path("id").asLong() == chunkId) return null;
        }
        return HttpStatus.FORBIDDEN;
    }

    /**
     * The knowledge service's base, file and retrieval endpoints are not resource scoped either, so the console route rule
     * (knowledge write in any scope) is not enough: each forwarded call is checked against the knowledge base or file it
     * names, and calls this console does not make are refused.
     */
    HttpStatus knowledgeWriteDenied(PlatformAuthenticatedSession session, String requestMethod, String path, byte[] body) {
        if (session == null || chunkScope == null) return HttpStatus.FORBIDDEN;
        String method = requestMethod.toUpperCase(java.util.Locale.ROOT);
        try {
            if ("POST".equals(method) && "/ai/knowledge/base".equals(path)) return knowledgeCreateDenied(session, body);
            var base = KNOWLEDGE_BASE_PATH.matcher(path);
            if ("DELETE".equals(method) && base.matches()) return scopeDenied(chunkScope.baseForWrite(session, decode(base.group(1))));
            var file = KNOWLEDGE_FILE_PATH.matcher(path);
            if ("DELETE".equals(method) && file.matches()) return scopeDenied(chunkScope.fileChunksForWrite(session, decode(file.group(1))));
            if ("POST".equals(method) && "/ai/retrieval/test".equals(path)) return retrievalDenied(session, body);
        } catch (org.springframework.web.server.ResponseStatusException refused) {
            return refused.getStatusCode().is4xxClientError() ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
        } catch (IllegalArgumentException invalid) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.FORBIDDEN;
    }

    /** A new knowledge base lands in the scope its request names (workspace "default" and WORKSPACE when left out). */
    private HttpStatus knowledgeCreateDenied(PlatformAuthenticatedSession session, byte[] body) {
        var request = readObject(body);
        if (request == null) return HttpStatus.BAD_REQUEST;
        String scope = textOr(request.get("scope"), "WORKSPACE");
        String workspace = textOr(request.get("workspaceId"), "default");
        String project = textOr(request.get("projectCode"), null);
        if (!List.of("SHARED", "WORKSPACE", "PROJECT").contains(scope) || "PROJECT".equals(scope) && project == null) return HttpStatus.BAD_REQUEST;
        return session.hasResourcePermission(com.enterprise.ai.control.identity.PlatformPermissions.KNOWLEDGE_WRITE, scope, workspace,
                "PROJECT".equals(scope) ? project : null) ? null : HttpStatus.FORBIDDEN;
    }

    /** A retrieval test reads every named knowledge base, with only the options the console sends. */
    private HttpStatus retrievalDenied(PlatformAuthenticatedSession session, byte[] body) {
        var request = readObject(body);
        if (request == null || !RETRIEVAL_FIELDS.containsAll(request.keySet())
                || !(request.get("knowledgeBaseCodes") instanceof List<?> codes) || codes.isEmpty() || codes.size() > 20) {
            return HttpStatus.BAD_REQUEST;
        }
        for (Object code : codes) {
            if (!(code instanceof String kbCode) || kbCode.isBlank()) return HttpStatus.BAD_REQUEST;
            HttpStatus denied = scopeDenied(chunkScope.read(session, McpPlatformKnowledgeCatalogPort.Operation.STATS, Map.of("kbCode", kbCode.trim())));
            if (denied != null) return denied;
        }
        return null;
    }

    private static HttpStatus scopeDenied(ResponseEntity<?> scoped) {
        if (scoped != null && scoped.getStatusCode().is2xxSuccessful()) return null;
        return scoped != null && scoped.getStatusCode().is4xxClientError() ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
    }

    private static Map<String, Object> readObject(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
        } catch (Exception invalid) {
            return null;
        }
    }

    private static String textOr(Object value, String fallback) {
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException("text expected");
        return text.isBlank() ? fallback : text.trim();
    }

    private static String decode(String segment) {
        return java.net.URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    /**
     * The model service's instance edit, archive and test endpoints are not project scoped, so a write is admitted only
     * when the signed-in user holds model:manage on the instance's project (and on the target project of an edit).
     */
    private HttpStatus modelWriteDenied(HttpServletRequest request, String modelId, byte[] body) {
        if (!(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE) instanceof PlatformAuthenticatedSession session)
                || modelScope == null) {
            return HttpStatus.FORBIDDEN;
        }
        ResponseEntity<?> scoped;
        try { scoped = modelScope.get(session, modelId); }
        catch (org.springframework.web.server.ResponseStatusException refused) {
            return refused.getStatusCode().is4xxClientError() ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (scoped == null || !scoped.getStatusCode().is2xxSuccessful() || !(scoped.getBody() instanceof Map<?, ?> envelope)
                || !(envelope.get("data") instanceof Map<?, ?> model)) {
            return scoped != null && scoped.getStatusCode().is4xxClientError() ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (!canManageModels(session, model.get("projectCode"))) return HttpStatus.FORBIDDEN;
        return requestedProjectDenied(session, body, false);
    }

    /** The saved instance a draft names: empty when none, null when the body is not a JSON object. */
    private static String draftInstanceId(byte[] body) {
        if (body == null || body.length == 0) return "";
        try {
            var draft = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (draft == null || !draft.isObject()) return null;
            var id = draft.get("id");
            if (id == null || id.isNull() || id.isTextual() && id.asText().isBlank()) return "";
            return id.isTextual() && id.asText().trim().matches("[A-Za-z0-9_-]{1,64}") ? id.asText().trim() : null;
        } catch (java.io.IOException invalid) {
            return null;
        }
    }

    /** Checks model:manage on a projectCode named in the body; an absent one means platform level only when creating. */
    private static HttpStatus requestedProjectDenied(PlatformAuthenticatedSession session, byte[] body, boolean creating) {
        com.fasterxml.jackson.databind.JsonNode requested = null;
        if (body != null && body.length > 0) {
            try { requested = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body); }
            catch (java.io.IOException invalid) { return HttpStatus.BAD_REQUEST; }
        }
        if (requested == null || !requested.has("projectCode")) {
            return creating && !canManageModels(session, null) ? HttpStatus.FORBIDDEN : null;
        }
        var target = requested.get("projectCode");
        if (!target.isNull() && !target.isTextual()) return HttpStatus.BAD_REQUEST;
        return canManageModels(session, target.isNull() ? null : target.asText()) ? null : HttpStatus.FORBIDDEN;
    }

    private static boolean canManageModels(PlatformAuthenticatedSession session, Object projectCode) {
        String project = projectCode instanceof String text && !text.isBlank() ? text.trim() : null;
        return project == null ? session.hasGlobalPermission(com.enterprise.ai.control.identity.PlatformPermissions.MODEL_MANAGE)
                : session.hasResourcePermission(com.enterprise.ai.control.identity.PlatformPermissions.MODEL_MANAGE, "PROJECT", null, project);
    }

    /** Model console call for trusted in-process platform adapters, which authorize the exact instance first. */
    ResponseEntity<byte[]> modelSend(HttpMethod method, String path, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        signModelHeaders(headers, method.name(), modelServiceUrl + path, body);
        try {
            return restTemplate.exchange(URI.create(modelServiceUrl + path), method,
                    new HttpEntity<>(body == null ? new byte[0] : body, headers), byte[].class);
        } catch (RestClientResponseException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(ex.getResponseBodyAsByteArray());
        } catch (ResourceAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /** Knowledge console call for trusted in-process platform adapters, which authorize the exact resource first. */
    ResponseEntity<byte[]> knowledgeSend(HttpMethod method, String path, Map<String, String> query, byte[] body) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(knowledgeServiceUrl).path(path);
        query.forEach(builder::queryParam);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return restTemplate.exchange(builder.encode().build().toUri(), method, new HttpEntity<>(body == null ? new byte[0] : body, headers), byte[].class);
        } catch (RestClientResponseException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(ex.getResponseBodyAsByteArray());
        } catch (ResourceAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @Autowired
    void setModelScope(com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort modelScope) {
        this.modelScope = modelScope;
    }

    @Autowired
    void setChunkScope(McpPlatformKnowledgeCatalogPort chunkScope) {
        this.chunkScope = chunkScope;
    }

    private ResponseEntity<byte[]> forward(String baseUrl, RequestEntity<byte[]> requestEntity,
                                           HttpServletRequest request, String unavailableCode) {
        URI target = UriComponentsBuilder.fromUriString(baseUrl)
                .path(request.getRequestURI())
                .query(StringUtils.hasText(request.getQueryString()) ? request.getQueryString() : null)
                .build(true)
                .toUri();
        HttpHeaders headers = forwardHeaders(requestEntity.getHeaders());
        if ("MODEL_SERVICE_UNAVAILABLE".equals(unavailableCode)) {
            signModelHeaders(headers, requestEntity.getMethod().name(), target.toString(), requestEntity.getBody());
        }
        HttpEntity<byte[]> entity = new HttpEntity<>(requestEntity.getBody(), headers);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(target, requestEntity.getMethod(), entity, byte[].class);
            return ResponseEntity.status(response.getStatusCode())
                    .headers(forwardHeaders(response.getHeaders()))
                    .body(response.getBody());
        } catch (RestClientResponseException ex) {
            return ResponseEntity.status(ex.getStatusCode())
                    .headers(forwardHeaders(ex.getResponseHeaders()))
                    .body(ex.getResponseBodyAsByteArray());
        } catch (ResourceAccessException ex) {
            String body = "{\"code\":\"" + unavailableCode + "\",\"message\":\"服务暂不可用，请稍后重试\"}";
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void signModelHeaders(HttpHeaders headers, String method, String target, byte[] body) {
        com.enterprise.ai.common.internalauth.ModelServiceRequestAuth.sign(modelServiceSecret,
                com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders.CALLER_CONTROL,
                method, target, body).forEach(headers::set);
    }

    static HttpHeaders forwardHeaders(HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        if (source != null) {
            source.forEach((name, values) -> {
                if (DROPPED_HEADERS.stream().noneMatch(dropped -> dropped.equalsIgnoreCase(name))) {
                    headers.put(name, values);
                }
            });
        }
        return headers;
    }

    private static void copyFlushing(InputStream input, OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            output.flush();
        }
    }

    private static String normalizeBaseUrl(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        if (!StringUtils.hasText(trimmed)) {
            return fallback;
        }
        return trimmed.replaceAll("/+$", "");
    }
}
