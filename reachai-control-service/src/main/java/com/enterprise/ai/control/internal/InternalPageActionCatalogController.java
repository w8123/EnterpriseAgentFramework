package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/control/page-actions")
@RequiredArgsConstructor
public class InternalPageActionCatalogController {

    private final PageCatalogApplicationService pageCatalog;

    @GetMapping("/{projectCode}/{pageKey}/{actionKey}")
    public ResponseEntity<PageActionCatalogEntry> getPageAction(@PathVariable String projectCode,
                                                                @PathVariable String pageKey,
                                                                @PathVariable String actionKey) {
        return lookup(projectCode, pageKey, actionKey);
    }

    @GetMapping("/lookup")
    public ResponseEntity<PageActionCatalogEntry> lookup(@RequestParam String projectCode,
                                                         @RequestParam String pageKey,
                                                         @RequestParam String actionKey) {
        return pageCatalog.findAction(projectCode, pageKey, actionKey)
                .map(row -> ResponseEntity.ok(toEntry(row)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<PageActionCatalogEntry>> list(
            @RequestParam String projectCode,
            @RequestParam String pageKey,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "500") int limit) {
        return ResponseEntity.ok(pageCatalog.listActions(
                        projectCode,
                        pageKey,
                        status,
                        limit)
                .stream()
                .map(this::toEntry)
                .toList());
    }

    private PageActionCatalogEntry toEntry(ActionView row) {
        return new PageActionCatalogEntry(
                row.id(),
                row.projectCode(),
                row.pageKey(),
                row.actionKey(),
                row.title(),
                row.description(),
                row.riskLevel(),
                row.confirmRequired(),
                row.permissionKey(),
                row.inputSchema(),
                row.outputSchema(),
                row.sampleArgs(),
                row.allowedAgentIds(),
                row.implementationRef(),
                row.metadata(),
                row.status());
    }

    public record PageActionCatalogEntry(
            Long id,
            String projectCode,
            String pageKey,
            String actionKey,
            String title,
            String description,
            String riskLevel,
            boolean confirmRequired,
            String permissionKey,
            Object inputSchema,
            Object outputSchema,
            Object sampleArgs,
            List<String> allowedAgentIds,
            String implementationRef,
            Object metadata,
            String status
    ) {
    }
}
