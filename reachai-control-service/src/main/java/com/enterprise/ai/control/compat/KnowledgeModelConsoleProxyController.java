package com.enterprise.ai.control.compat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final String knowledgeServiceUrl;
    private final String modelServiceUrl;

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
        return forward(modelServiceUrl, requestEntity, request, "MODEL_SERVICE_UNAVAILABLE");
    }

    @PostMapping(path = MODEL_STREAM_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> modelStream(RequestEntity<byte[]> requestEntity,
                                                             HttpServletResponse servletResponse) {
        byte[] body = requestEntity.getBody() == null ? new byte[0] : requestEntity.getBody();
        HttpRequest upstream = HttpRequest.newBuilder()
                .uri(URI.create(modelServiceUrl + MODEL_STREAM_PATH))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
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

    private ResponseEntity<byte[]> forward(String baseUrl, RequestEntity<byte[]> requestEntity,
                                           HttpServletRequest request, String unavailableCode) {
        URI target = UriComponentsBuilder.fromUriString(baseUrl)
                .path(request.getRequestURI())
                .query(StringUtils.hasText(request.getQueryString()) ? request.getQueryString() : null)
                .build(true)
                .toUri();
        HttpEntity<byte[]> entity = new HttpEntity<>(requestEntity.getBody(), forwardHeaders(requestEntity.getHeaders()));
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
