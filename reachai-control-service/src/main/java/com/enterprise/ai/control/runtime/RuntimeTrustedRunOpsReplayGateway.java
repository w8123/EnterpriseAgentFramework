package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Signs the exact replay command bytes with the current platform operator, never a source-run user. */
@Service
public class RuntimeTrustedRunOpsReplayGateway {
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper mapper;
    private final String runtimeUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public RuntimeTrustedRunOpsReplayGateway(InternalServiceAuthSigner signer, ObjectMapper mapper,
            @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeUrl) {
        this.signer = signer;
        this.mapper = mapper;
        this.runtimeUrl = runtimeUrl.replaceAll("/+$", "");
    }

    public ResponseEntity<Map<String, Object>> replay(String traceId, Map<String, Object> command, String actorId) {
        if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Replay traceId is invalid");
        }
        String path = "/internal/runtime/runops/traces/" + traceId + "/replay";
        try {
            byte[] bytes = mapper.writeValueAsBytes(command);
            Map<String, String> headers = signer.sign("POST", path,
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", actorId, bytes);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(runtimeUrl + path))
                    .timeout(Duration.ofMinutes(3)).header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            headers.forEach(request::header);
            HttpResponse<java.io.InputStream> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] result;
            try (var stream = response.body()) {
                result = stream.readNBytes(8 * 1024 * 1024 + 1);
            }
            if (result.length > 8 * 1024 * 1024) throw new IOException("Replay response exceeds limit");
            Map<String, Object> body = result.length == 0 ? Map.of()
                    : mapper.readValue(result, new TypeReference<Map<String, Object>>() {});
            return ResponseEntity.status(response.statusCode()).body(body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("trusted replay interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("trusted replay failed", ex);
        }
    }
}
