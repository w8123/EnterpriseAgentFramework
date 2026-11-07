package com.enterprise.ai.capability.catalog.httpapi;

import lombok.RequiredArgsConstructor;
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
@RestController
@RequestMapping("/api/scan-projects/{projectId}/manual-http-apis")
@RequiredArgsConstructor
public class ManualHttpApiController {
    static final int MAX_BATCH = 200;
    private final ManualHttpApiService manual;

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

    public record BatchRequest(List<ManualHttpApiService.Request> apis) { }

    private Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "code", code, "message", message == null ? "请求无效" : message);
    }
}
