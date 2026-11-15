package com.enterprise.ai.capability.catalog.httpapi;

import com.enterprise.ai.capability.catalog.enrichment.CatalogSubject;
import com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Console project resource (reached only through Control's /api/scan-projects/{id}/** proxy, which
 * checks the caller's project write permission): people and their AI tools add HTTP APIs by hand.
 */
@Slf4j
@RestController
@RequestMapping("/api/scan-projects/{projectId}/manual-http-apis")
@RequiredArgsConstructor
public class ManualHttpApiController {
    static final int MAX_BATCH = 200;
    private final ManualHttpApiService manual;
    private final HttpApiDocumentImportService documents;
    private final ObjectProvider<CapabilitySemanticCatalogService> semantic;

    /**
     * Reads a whole API document (OpenAPI 3 / Swagger 2 / Postman / HAR; pasted, uploaded or fetched by URL)
     * and returns the operations it describes. Nothing is saved; the page then posts the ticked ones to /batch.
     */
    @PostMapping("/import/preview")
    public ResponseEntity<?> preview(@PathVariable long projectId,
                                     @RequestBody(required = false) HttpApiDocumentImportService.PreviewRequest request) {
        try {
            return ResponseEntity.ok(documents.preview(projectId, request));
        } catch (ManualHttpApiService.NotFound missing) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_IMPORT_INVALID", invalid.getMessage()));
        }
    }

    /**
     * Reads a Dify app (by its API key) or n8n webhooks and returns them as API candidates, so workflows that
     * already run there can be called by agents and employees' AI tools. Nothing is saved.
     */
    @PostMapping("/import/platform-preview")
    public ResponseEntity<?> platformPreview(@PathVariable long projectId,
                                             @RequestBody(required = false) WorkflowPlatformReader.Request request) {
        try {
            return ResponseEntity.ok(documents.previewPlatform(projectId, request));
        } catch (ManualHttpApiService.NotFound missing) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_IMPORT_INVALID", invalid.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> save(@PathVariable long projectId, @RequestBody(required = false) ManualHttpApiService.Request request) {
        try {
            return ResponseEntity.ok(manual.save(projectId, request));
        } catch (ManualHttpApiService.NotFound missing) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_MANUAL_INVALID", invalid.getMessage()));
        }
    }

    /** Batch upload (AI tools after scanning code). Each item is saved on its own; one bad item never blocks the rest. */
    @PostMapping("/batch")
    public ResponseEntity<?> batch(@PathVariable long projectId, @RequestBody(required = false) BatchRequest request) {
        if (request == null || request.apis() == null || request.apis().isEmpty()) {
            return ResponseEntity.badRequest().body(error("HTTP_API_MANUAL_INVALID", "请至少提供一个 API"));
        }
        if (request.apis().size() > MAX_BATCH) {
            return ResponseEntity.badRequest().body(error("HTTP_API_MANUAL_INVALID", "一次最多上传 " + MAX_BATCH + " 个 API，请分批上传"));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int saved = 0;
        for (int index = 0; index < request.apis().size(); index++) {
            ManualHttpApiService.Request item = request.apis().get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index);
            row.put("httpMethod", item == null ? null : item.httpMethod());
            row.put("path", item == null ? null : item.path());
            try {
                ManualHttpApiService.Result result = manual.save(projectId, item);
                row.put("ok", true);
                row.put("apiId", result.apiId());
                row.put("created", result.created());
                row.put("status", result.status());
                row.put("described", describe(projectId, result.apiId(), item));
                saved++;
            } catch (ManualHttpApiService.NotFound missing) {
                return ResponseEntity.notFound().build();
            } catch (IllegalArgumentException invalid) {
                row.put("ok", false);
                row.put("message", invalid.getMessage());
            }
            results.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saved", saved);
        body.put("failed", results.size() - saved);
        body.put("results", results);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{apiId}")
    public ResponseEntity<?> remove(@PathVariable long projectId, @PathVariable long apiId) {
        try {
            return manual.remove(projectId, apiId)
                    ? ResponseEntity.ok(Map.of("removed", true))
                    : ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("HTTP_API_MANUAL_SOURCE_NOT_FOUND", "这个 API 没有手工来源，不能在这里删除"));
        } catch (ManualHttpApiService.NotFound missing) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(error("HTTP_API_MANUAL_INVALID", invalid.getMessage()));
        }
    }

    /** Imported summary/description become the API's semantic doc, only when it has none yet. */
    private boolean describe(long projectId, Long apiId, ManualHttpApiService.Request item) {
        if (apiId == null || item == null) return false;
        String summary = item.summary() == null ? "" : item.summary().trim();
        String description = item.description() == null ? "" : item.description().trim();
        if (summary.isEmpty() && description.isEmpty()) return false;
        String content = summary.isEmpty() ? description
                : description.isEmpty() || description.equals(summary) ? summary : summary + "\n\n" + description;
        CapabilitySemanticCatalogService docs = semantic.getIfAvailable();
        if (docs == null) return false;
        try {
            return docs.seedIfAbsent(projectId, CatalogSubject.HTTP_API, apiId, content);
        } catch (RuntimeException failed) {
            log.warn("Imported API description not saved: project={}, api={}, reason={}", projectId, apiId, failed.getMessage());
            return false;
        }
    }

    public record BatchRequest(List<ManualHttpApiService.Request> apis) { }

    private Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "code", code, "message", message == null ? "请求无效" : message);
    }
}
