package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.internalauth.KnowledgeBizIndexConsoleAuthFilter;
import com.enterprise.ai.service.impl.KnowledgeSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Set;

/** All bodies are HMAC verified before decoding; the owner repeats optimistic checks while holding the row lock. */
@RestController
@RequestMapping(value = KnowledgeBizIndexConsoleAuthFilter.CATALOG_PREFIX + "/settings", consumes = "application/json", produces = "application/json")
@RequiredArgsConstructor
public class KnowledgeSettingsInternalController {
    private final KnowledgeSettingsService settings;

    @PostMapping("/context")
    public ApiResult<Map<String, Object>> context(HttpServletRequest request, @RequestBody Map<String, Object> command) {
        verify(request, command, Set.of("kbCode"));
        return ApiResult.ok(settings.context(code(command)));
    }
    @PostMapping("/preview")
    public ApiResult<Map<String, Object>> preview(HttpServletRequest request, @RequestBody Map<String, Object> command) {
        verify(request, command, Set.of("kbCode", "baseRevision", "changes"));
        return ApiResult.ok(settings.preview(code(command), revision(command), changes(command)));
    }
    @PostMapping("/save")
    public ApiResult<Map<String, Object>> save(HttpServletRequest request, @RequestBody Map<String, Object> command) {
        verify(request, command, Set.of("kbCode", "baseRevision", "changes"));
        return ApiResult.ok(settings.save(code(command), revision(command), changes(command)));
    }
    private void verify(HttpServletRequest request, Map<String, Object> command, Set<String> fields) {
        if (!(request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_ACTOR_ATTRIBUTE) instanceof String actor) || actor.isBlank()
                || !(request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_TENANT_ATTRIBUTE) instanceof String tenant) || tenant.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified Control identity is required");
        }
        if (command == null || !fields.equals(command.keySet())) throw invalid();
    }
    private String code(Map<String, Object> body) { if (body.get("kbCode") instanceof String code) return code; throw invalid(); }
    private String revision(Map<String, Object> body) { if (body.get("baseRevision") instanceof String revision) return revision; throw invalid(); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> changes(Map<String, Object> body) {
        if (body.get("changes") instanceof Map<?, ?> changes && changes.keySet().stream().allMatch(String.class::isInstance)) return (Map<String, Object>) changes;
        throw invalid();
    }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Knowledge settings command"); }
}
