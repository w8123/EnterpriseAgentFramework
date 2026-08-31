package com.enterprise.ai.capability.externalapi;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/api-market")
@RequiredArgsConstructor
public class ExternalApiCatalogController {

    private final ExternalApiCatalogService catalogService;

    @GetMapping("/entries")
    public ResponseEntity<ExternalApiCatalogViews.PageView> listEntries(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String authType,
            @RequestParam(required = false) String verificationStatus,
            @RequestParam(required = false) String specStatus,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean specAvailable) {
        return ResponseEntity.ok(catalogService.listEntries(
                current, size, keyword, category, authType, verificationStatus, specStatus, source, specAvailable));
    }

    @GetMapping("/entries/{entryKey}")
    public ResponseEntity<ExternalApiCatalogViews.EntryDetail> getEntry(@PathVariable String entryKey) {
        return ResponseEntity.ok(catalogService.getEntry(entryKey));
    }

    @GetMapping("/stats")
    public ResponseEntity<ExternalApiCatalogViews.StatsView> stats() {
        return ResponseEntity.ok(catalogService.stats());
    }

    @GetMapping("/categories")
    public ResponseEntity<List<ExternalApiCatalogViews.CategoryView>> categories() {
        return ResponseEntity.ok(catalogService.categories());
    }

    @GetMapping("/sources")
    public ResponseEntity<List<ExternalApiCatalogViews.SourceView>> sources() {
        return ResponseEntity.ok(catalogService.sources());
    }

    @GetMapping("/integrations")
    public ResponseEntity<List<ExternalApiCatalogViews.IntegrationView>> integrations(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(catalogService.listIntegrations(projectId, projectCode, status));
    }

    @PostMapping("/entries/{entryKey}/integrations")
    public ResponseEntity<ExternalApiCatalogViews.IntegrationView> createIntegration(
            @PathVariable String entryKey,
            @RequestBody ExternalApiCatalogViews.IntegrationCreateRequest request) {
        return ResponseEntity.ok(catalogService.createIntegration(entryKey, request));
    }

    @PutMapping("/integrations/{integrationId}/status")
    public ResponseEntity<ExternalApiCatalogViews.IntegrationView> updateIntegrationStatus(
            @PathVariable Long integrationId,
            @RequestBody ExternalApiCatalogViews.IntegrationStatusRequest request) {
        return ResponseEntity.ok(catalogService.updateIntegrationStatus(integrationId, request));
    }
}
