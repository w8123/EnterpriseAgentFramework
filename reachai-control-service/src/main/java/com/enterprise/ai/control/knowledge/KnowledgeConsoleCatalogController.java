package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact GET mappings supersede the compatibility proxy and share their resource boundary with MCP. */
@RestController
@RequiredArgsConstructor
public class KnowledgeConsoleCatalogController {
    private final KnowledgeConsoleCatalogService catalog;
    private final PlatformRequestAuthorization authorization;

    @GetMapping("/ai/knowledge/base/list")
    public ResponseEntity<?> bases(HttpServletRequest request) { return read(request, Operation.BASES, Map.of()); }
    @GetMapping("/ai/knowledge/dependencies")
    public ResponseEntity<?> dependencies(HttpServletRequest request) { return read(request, Operation.DEPENDENCIES, Map.of()); }
    @GetMapping("/ai/knowledge/kb/{kbCode}/files")
    public ResponseEntity<?> files(HttpServletRequest request, @PathVariable String kbCode) { return read(request, Operation.FILES, Map.of("kbCode", kbCode)); }
    @GetMapping("/ai/knowledge/kb/{kbCode}/stats")
    public ResponseEntity<?> stats(HttpServletRequest request, @PathVariable String kbCode) { return read(request, Operation.STATS, Map.of("kbCode", kbCode)); }
    @GetMapping("/ai/knowledge/kb/{kbCode}/ops-dashboard")
    public ResponseEntity<?> dashboard(HttpServletRequest request, @PathVariable String kbCode) { return read(request, Operation.DASHBOARD, Map.of("kbCode", kbCode)); }
    @GetMapping("/ai/knowledge/kb/{kbCode}/chunks")
    public ResponseEntity<?> chunks(HttpServletRequest request, @PathVariable String kbCode, @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer enabled, @RequestParam(required = false) Integer limit) {
        var parameters = new LinkedHashMap<String, Object>(); parameters.put("kbCode", kbCode);
        put(parameters, "keyword", keyword); put(parameters, "enabled", enabled); put(parameters, "limit", limit);
        return read(request, Operation.CHUNKS, parameters);
    }
    @GetMapping("/ai/knowledge/kb/{kbCode}/hit-logs")
    public ResponseEntity<?> hits(HttpServletRequest request, @PathVariable String kbCode, @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) Boolean lowConfidenceOnly, @RequestParam(required = false) String source) {
        var parameters = new LinkedHashMap<String, Object>(); parameters.put("kbCode", kbCode);
        put(parameters, "limit", limit); put(parameters, "lowConfidenceOnly", lowConfidenceOnly); put(parameters, "source", source);
        return read(request, Operation.HIT_LOGS, parameters);
    }
    @GetMapping("/ai/file/{fileId}/chunks")
    public ResponseEntity<?> fileChunks(HttpServletRequest request, @PathVariable String fileId) { return read(request, Operation.FILE_CHUNKS, Map.of("fileId", fileId)); }

    private ResponseEntity<?> read(HttpServletRequest request, Operation operation, Map<String, Object> parameters) {
        var response = catalog.read(authorization.requirePermission(request, PlatformPermissions.KNOWLEDGE_READ), operation, parameters);
        if (!response.getStatusCode().is2xxSuccessful()) return ResponseEntity.status(response.getStatusCode()).build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(ApiResult.ok(response.getBody()));
    }
    private void put(Map<String, Object> target, String key, Object value) { if (value != null) target.put(key, value); }
}
