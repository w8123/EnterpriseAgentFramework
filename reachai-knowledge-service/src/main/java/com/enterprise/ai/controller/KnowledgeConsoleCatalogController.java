package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.config.KnowledgeDependencyInfoContributor;
import com.enterprise.ai.domain.dto.DocumentImportResourceScope;
import com.enterprise.ai.internalauth.KnowledgeBizIndexConsoleAuthFilter;
import com.enterprise.ai.service.DocumentImportJobService;
import com.enterprise.ai.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Signed, read-only console projections. Control checks the canonical scope before returning any content. */
@RestController
@RequestMapping(KnowledgeBizIndexConsoleAuthFilter.CATALOG_PREFIX)
@RequiredArgsConstructor
public class KnowledgeConsoleCatalogController {
    private final KnowledgeService knowledge;
    private final DocumentImportJobService scopes;
    private final KnowledgeDependencyInfoContributor dependencies;

    @PostMapping(value = "/read", consumes = "application/json", produces = "application/json")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> read(HttpServletRequest request, @RequestBody Map<String, Object> command) {
        if (!(request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_ACTOR_ATTRIBUTE) instanceof String actor) || actor.isBlank()
                || !(request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_TENANT_ATTRIBUTE) instanceof String tenant) || tenant.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified Control identity is required");
        }
        String operation = text(command, "operation", true);
        Set<String> fields = switch (operation) {
            case "BASES", "DEPENDENCIES" -> Set.of("operation");
            case "FILES", "STATS", "DASHBOARD" -> Set.of("operation", "kbCode");
            case "CHUNKS" -> Set.of("operation", "kbCode", "keyword", "enabled", "limit");
            case "HIT_LOGS" -> Set.of("operation", "kbCode", "limit", "lowConfidenceOnly", "source");
            case "FILE_CHUNKS" -> Set.of("operation", "fileId");
            default -> throw invalid();
        };
        if (!fields.containsAll(command.keySet())) throw invalid();
        DocumentImportResourceScope scope = null;
        String kbCode = fields.contains("kbCode") ? identifier(command, "kbCode") : null;
        String fileId = fields.contains("fileId") ? identifier(command, "fileId") : null;
        if (kbCode != null) scope = scopes.describeKnowledgeBaseScope(kbCode);
        if (fileId != null) scope = scopes.describeFileScope(fileId);
        // The scope and content are read in one repeatable database snapshot, so a concurrent move cannot mix them.
        Object data = switch (operation) {
            case "BASES" -> knowledge.listAll().stream().map(base -> {
                // Legacy rows use the owner's existing default-scope rules, exactly as import/file authorization does.
                var canonical = scopes.describeKnowledgeBaseScope(base.getCode());
                base.setWorkspaceId(canonical.getWorkspaceId());
                base.setProjectCode(canonical.getProjectCode());
                base.setScope(canonical.getScope());
                return base;
            }).toList();
            case "DEPENDENCIES" -> dependencies.dependencies();
            case "FILES" -> knowledge.getFilesByKbCode(kbCode);
            case "STATS" -> knowledge.getStats(kbCode);
            case "DASHBOARD" -> knowledge.getOpsDashboard(kbCode);
            case "CHUNKS" -> knowledge.listChunks(kbCode, text(command, "keyword", false), enabled(command), number(command, 200, 500));
            case "HIT_LOGS" -> knowledge.listHitLogs(kbCode, number(command, 50, 200), flag(command), source(command));
            case "FILE_CHUNKS" -> knowledge.getChunksByFileId(fileId);
            default -> throw invalid();
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", 1);
        result.put("resource", scope);
        result.put("data", data);
        return ApiResult.ok(result);
    }

    private static String identifier(Map<String, Object> body, String key) {
        String value = text(body, key, true);
        if (value.length() > 64 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) throw invalid();
        return value;
    }
    private static String text(Map<String, Object> body, String key, boolean required) {
        Object value = body.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256) throw invalid();
        return text.trim();
    }
    private static int number(Map<String, Object> body, int fallback, int maximum) {
        Object value = body.get("limit");
        if (value == null) return fallback;
        if (!(value instanceof Integer count) || count < 1 || count > maximum) throw invalid();
        return count;
    }
    private static Integer enabled(Map<String, Object> body) {
        Object value = body.get("enabled");
        if (value == null) return null;
        if (!(value instanceof Integer flag) || flag < 0 || flag > 1) throw invalid();
        return flag;
    }
    private static Boolean flag(Map<String, Object> body) {
        Object value = body.get("lowConfidenceOnly");
        if (value == null || value instanceof Boolean) return (Boolean) value;
        throw invalid();
    }
    private static String source(Map<String, Object> body) {
        String value = text(body, "source", false);
        if (value != null && !Set.of("CONSOLE", "RUNTIME").contains(value)) throw invalid();
        return value;
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Knowledge catalog command");
    }
}
