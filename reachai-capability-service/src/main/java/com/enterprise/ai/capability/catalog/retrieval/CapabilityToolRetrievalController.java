package com.enterprise.ai.capability.catalog.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tool-retrieval")
@RequiredArgsConstructor
public class CapabilityToolRetrievalController {

    private final CapabilityToolRetrievalService toolRetrievalService;

    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(new SearchResponse(List.of(), "query 不能为空"));
        }
        int topK = request.topK() == null ? 0 : request.topK();
        CapabilityRetrievalScope scope = new CapabilityRetrievalScope(
                request.projectIds(),
                request.moduleIds(),
                request.toolWhitelist(),
                request.enabledOnly() == null || request.enabledOnly()
        );
        List<CapabilityToolCandidate> candidates = toolRetrievalService.retrieve(
                request.query(), scope, topK, request.minScore());
        return ResponseEntity.ok(new SearchResponse(candidates, null));
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse(
                true,
                true,
                "capability_tool_definition",
                "KEYWORD_FALLBACK_READY",
                "Capability Tool Retrieval is served locally from capability_tool_definition keyword matching"
        ));
    }

    public record SearchRequest(
            String query,
            Integer topK,
            List<Long> projectIds,
            List<Long> moduleIds,
            List<Long> toolWhitelist,
            Boolean enabledOnly,
            Double minScore
    ) {
    }

    public record SearchResponse(List<CapabilityToolCandidate> candidates, String message) {
    }

    public record HealthResponse(
            boolean enabled,
            boolean ready,
            String collectionName,
            String status,
            String message
    ) {
    }
}
