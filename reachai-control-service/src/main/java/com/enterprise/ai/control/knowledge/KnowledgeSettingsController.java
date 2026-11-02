package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.common.knowledge.KnowledgeSettingsProtocol;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Map;
import java.util.Set;

/** Shared browser editing protocol; settings are saved atomically against the loaded owner revision. */
@RestController
@RequiredArgsConstructor
public class KnowledgeSettingsController {
    private final KnowledgeSettingsGateway settings;
    private final PlatformRequestAuthorization authorization;

    /** Literal mappings beat the broad compatibility proxy and reject old clients before any owner call. */
    @PutMapping({"/ai/knowledge/base", "/ai/knowledge/kb/{kbCode}/config"})
    public ResponseEntity<?> legacyWrite(HttpServletRequest request) {
        authorization.requirePermission(request, PlatformPermissions.KNOWLEDGE_WRITE);
        return ResponseEntity.status(HttpStatus.CONFLICT).header("Cache-Control", "no-store")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(KnowledgeSettingsProtocol.revisionRequired());
    }

    @GetMapping("/ai/knowledge/kb/{kbCode}/settings-context")
    public ResponseEntity<?> context(HttpServletRequest request, @PathVariable String kbCode) {
        return respond(settings.context(authorization.requireAuthenticated(request), kbCode));
    }
    @PostMapping("/ai/knowledge/kb/{kbCode}/settings-preview")
    public ResponseEntity<?> preview(HttpServletRequest request, @PathVariable String kbCode, @RequestBody Map<String, Object> body) {
        validate(body);
        return respond(settings.preview(authorization.requireAuthenticated(request), kbCode, (String) body.get("baseRevision"), changes(body)));
    }
    @PutMapping("/ai/knowledge/kb/{kbCode}/settings-if-unchanged")
    public ResponseEntity<?> save(HttpServletRequest request, @PathVariable String kbCode, @RequestBody Map<String, Object> body) {
        validate(body);
        return respond(settings.save(authorization.requireAuthenticated(request), kbCode, (String) body.get("baseRevision"), changes(body)));
    }
    private void validate(Map<String, Object> body) {
        if (body == null || !Set.of("baseRevision", "changes").equals(body.keySet()) || !(body.get("baseRevision") instanceof String)
                || !(body.get("changes") instanceof Map<?, ?>)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid settings command");
    }
    @SuppressWarnings("unchecked") private Map<String, Object> changes(Map<String, Object> body) { return (Map<String, Object>) body.get("changes"); }
    private ResponseEntity<?> respond(ResponseEntity<?> response) {
        if (response == null) return ResponseEntity.status(502).build();
        if (!response.getStatusCode().is2xxSuccessful()) return ResponseEntity.status(response.getStatusCode()).build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiResult.ok(response.getBody()));
    }
}
