package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.platform.PlatformEmbedSessionService;
import com.enterprise.ai.control.platform.PlatformEmbedTokenException;
import com.enterprise.ai.control.platform.PlatformEmbedTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies the page token in Control and signs only the resulting identity to Runtime. */
@Service
public class ControlPageAssistantSmokeGateway {
    static final String PATH = "/internal/runtime/workflows/ai-coding/page-assistant/smoke-test";
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private final PlatformEmbedTokenService tokens;
    private final PlatformEmbedSessionService sessions;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String runtimeUrl;

    public ControlPageAssistantSmokeGateway(PlatformEmbedTokenService tokens, PlatformEmbedSessionService sessions,
            InternalServiceAuthSigner signer, ObjectMapper mapper,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeUrl) {
        this.tokens = tokens; this.sessions = sessions; this.signer = signer; this.mapper = mapper;
        this.runtimeUrl = runtimeUrl.replaceAll("/+$", "");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public ResponseEntity<Object> execute(String workflowId, String expectedProject, String rawToken,
                                          Map<String, Object> request) {
        if (!StringUtils.hasText(rawToken) || !StringUtils.hasText(expectedProject)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "business page authorization is required");
        }
        try {
            var claims = tokens.verify(rawToken.trim());
            Object rawContext = request == null ? null : request.get("runtimeContext");
            Map<?, ?> context = rawContext instanceof Map<?, ?> map ? map : Map.of();
            String sessionId = firstText(context.get("embedSessionId"), context.get("sessionId"));
            if (!StringUtils.hasText(sessionId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runtimeContext.embedSessionId is required");
            }
            var session = sessions.requireActiveSession(sessionId, claims);
            if (!expectedProject.equals(session.getProjectCode()) || !StringUtils.hasText(session.getTenantId())
                    || !StringUtils.hasText(session.getExternalUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "page session does not belong to the workflow project");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("workflowId", workflowId); body.put("projectCode", session.getProjectCode());
            body.put("sessionId", session.getSessionId()); body.put("request", request == null ? Map.of() : request);
            byte[] payload = mapper.writeValueAsBytes(body);
            if (new String(payload, java.nio.charset.StandardCharsets.UTF_8).contains(rawToken.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "business page authorization must be supplied only in the request header");
            }
            var headers = signer.sign("POST", PATH, "EMBED_SESSION", session.getTenantId(), session.getExternalUserId(), payload);
            var builder = HttpRequest.newBuilder(URI.create(runtimeUrl + PATH)).timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json").header("Accept", "application/json");
            headers.forEach(builder::header);
            var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (var stream = response.body()) { responseBytes = stream.readNBytes(MAX_RESPONSE + 1); }
            if (responseBytes.length > MAX_RESPONSE) throw new java.io.IOException("runtime response too large");
            Object responseBody = responseBytes.length == 0 ? null : mapper.readValue(responseBytes, Object.class);
            return ResponseEntity.status(response.statusCode()).body(responseBody);
        } catch (PlatformEmbedTokenException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "business page authorization is invalid");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "page smoke test was interrupted");
        } catch (java.io.IOException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "page smoke test service is unavailable");
        }
    }

    private static String firstText(Object first, Object second) {
        for (Object value : new Object[]{first, second}) {
            if (value instanceof String text && StringUtils.hasText(text)) return text.trim();
        }
        return null;
    }
}
