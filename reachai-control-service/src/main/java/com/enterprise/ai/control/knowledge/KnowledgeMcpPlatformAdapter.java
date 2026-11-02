package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

/** Uses the console's signed Knowledge gateway and decodes only bounded JSON responses. */
@Component
@RequiredArgsConstructor
final class KnowledgeMcpPlatformAdapter implements McpPlatformKnowledgePort {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final KnowledgeBizIndexConsoleController indexes;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> listBusinessIndexes(PlatformAuthenticatedSession actor) {
        return scope.run(actor, request -> decode(() -> indexes.list(request)));
    }

    @Override
    public ResponseEntity<?> getBusinessIndex(PlatformAuthenticatedSession actor, String indexCode) {
        return scope.run(actor, request -> decode(() -> indexes.detail(request, indexCode)));
    }

    @Override
    public ResponseEntity<?> businessIndexStats(PlatformAuthenticatedSession actor, String indexCode) {
        return scope.run(actor, request -> decode(() -> indexes.stats(request, indexCode)));
    }

    @Override
    public ResponseEntity<?> searchBusinessIndex(PlatformAuthenticatedSession actor, String indexCode,
                                                  Map<String, Object> body) {
        return scope.run(actor, request -> decode(() -> indexes.search(request, indexCode, json.valueToTree(body))));
    }

    @Override
    public ResponseEntity<?> changeBusinessIndex(PlatformAuthenticatedSession actor, IndexChange change, String indexCode,
                                                  Map<String, Object> body) {
        return scope.run(actor, request -> decode(() -> switch (change) {
            case CREATE -> indexes.create(request, json.valueToTree(body));
            case UPDATE -> indexes.update(request, indexCode, json.valueToTree(body));
            case DELETE -> indexes.delete(request, indexCode);
            case REBUILD -> indexes.rebuild(request, indexCode);
        }));
    }

    private ResponseEntity<?> decode(Supplier<ResponseEntity<byte[]>> action) {
        ResponseEntity<byte[]> response = action.get();
        if (response == null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        if (!response.getStatusCode().is2xxSuccessful()) return ResponseEntity.status(response.getStatusCode()).build();
        byte[] body = response.getBody();
        if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        try {
            var parsed = json.readTree(body);
            return parsed == null ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).build() : ResponseEntity.ok(parsed);
        } catch (IOException invalid) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
