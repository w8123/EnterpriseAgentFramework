package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformUploadedFile;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeImportPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
class KnowledgeDocumentImportMcpAdapter implements McpPlatformKnowledgeImportPort {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Map<Operation, String> EVENTS = Map.of(
            Operation.COMMIT, "KNOWLEDGE_DOCUMENT_IMPORT_COMMITTED",
            Operation.RETRY, "KNOWLEDGE_DOCUMENT_IMPORT_RETRIED",
            Operation.CANCEL, "KNOWLEDGE_DOCUMENT_IMPORT_CANCELLED");
    private final KnowledgeDocumentImportController controller;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<?> job(PlatformAuthenticatedSession actor, String jobId, boolean includePreview) {
        return unwrap(controller.platformJob(actor, jobId, includePreview), true);
    }

    @Override
    public ResponseEntity<?> mutate(PlatformAuthenticatedSession actor, String jobId, Operation operation) {
        return unwrap(controller.platformMutateJob(actor, jobId, operation.name().toLowerCase(java.util.Locale.ROOT), EVENTS.get(operation)),
                operation != Operation.CANCEL);
    }

    @Override
    public ResponseEntity<?> reparse(PlatformAuthenticatedSession actor, String fileId) {
        ResponseEntity<byte[]> response = controller.platformReparse(actor, fileId);
        if (response == null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        if (!response.getStatusCode().is2xxSuccessful()) return ResponseEntity.status(response.getStatusCode()).build();
        try {
            byte[] body = response.getBody();
            if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            JsonNode envelope = json.readTree(body);
            if (!envelope.path("code").isInt() || envelope.path("code").intValue() != 200) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            return ResponseEntity.ok(envelope.path("data").isMissingNode() ? json.createObjectNode() : envelope.path("data"));
        } catch (Exception invalid) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @Override
    public ResponseEntity<?> submit(PlatformAuthenticatedSession actor, String fileName, byte[] content, String knowledgeBaseCode,
                                    String chunkStrategy, int chunkSize, int chunkOverlap) {
        return unwrap(controller.platformSubmit(actor, new PlatformUploadedFile(fileName, content), knowledgeBaseCode,
                chunkStrategy, chunkSize, chunkOverlap, null), true);
    }

    /** Owner error text never crosses this boundary; callers re-read the job to learn its state. */
    private ResponseEntity<?> unwrap(ResponseEntity<byte[]> response, boolean dataRequired) {
        if (response == null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        if (!response.getStatusCode().is2xxSuccessful()) return ResponseEntity.status(response.getStatusCode()).build();
        try {
            byte[] body = response.getBody();
            if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            JsonNode envelope = json.readTree(body);
            if (!envelope.path("code").isInt() || envelope.path("code").intValue() != 200) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            JsonNode data = envelope.path("data");
            if (dataRequired && (!data.isObject() || !data.path("jobId").isTextual())) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            return ResponseEntity.ok(dataRequired ? data : json.createObjectNode());
        } catch (Exception invalid) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
