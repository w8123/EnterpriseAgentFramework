package com.enterprise.ai.control.pageworkbench.api;

import com.enterprise.ai.control.pageworkbench.application.PageAnalysisApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageMapSummaryApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchPublishedApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchPageReadinessApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageAccessCenterOverviewApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowDeliveryApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowDeliveryApplicationService.DeliveryCommand;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.FindingView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ManualPageCommand;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageMapSummaryView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageIntegrationReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageAccessCenterOverviewView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDeliveryView;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissions;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/registry/projects/{projectCode}/page-workbench")
@RequiredArgsConstructor
public class PageWorkbenchConsoleController {

    private final PageCatalogApplicationService pageCatalog;
    private final PageAnalysisApplicationService pageAnalysis;
    private final PageMapSummaryApplicationService pageMapSummaryService;
    private final PageWorkbenchPublishedApplicationService publishedService;
    private final PageWorkbenchPageReadinessApplicationService
            pageReadinessService;
    private final PageWorkbenchWorkflowDeliveryApplicationService
            workflowDeliveryService;
    private final PageAccessCenterOverviewApplicationService
            accessCenterOverviewService;

    @GetMapping("/access-center")
    public ResponseEntity<PageAccessCenterOverviewView> accessCenter(
            @PathVariable String projectCode) {
        return ResponseEntity.ok(accessCenterOverviewService.overview(projectCode));
    }

    @GetMapping("/pages")
    public ResponseEntity<List<PageView>> pages(
            @PathVariable String projectCode,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(pageCatalog.listPages(projectCode, includeArchived));
    }

    @GetMapping("/pages/{pageId}")
    public ResponseEntity<PageView> page(
            @PathVariable String projectCode,
            @PathVariable Long pageId) {
        return pageCatalog.findPage(projectCode, pageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/pages/{pageId}/readiness")
    public ResponseEntity<PageIntegrationReadinessView> pageReadiness(
            @PathVariable String projectCode,
            @PathVariable Long pageId) {
        return ResponseEntity.ok(pageReadinessService.evaluate(
                projectCode,
                pageId));
    }

    @PostMapping("/pages")
    public ResponseEntity<PageView> createPage(
            @PathVariable String projectCode,
            @RequestBody ManualPageCommand command) {
        return ResponseEntity.ok(pageCatalog.createManualPage(projectCode, command));
    }

    @GetMapping("/page-map")
    public ResponseEntity<PageMapSummaryView> pageMap(@PathVariable String projectCode) {
        return ResponseEntity.ok(pageMapSummaryService.summary(projectCode));
    }

    @GetMapping("/findings")
    public ResponseEntity<List<FindingView>> findings(
            @PathVariable String projectCode,
            @RequestParam(required = false) Long pageId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(pageAnalysis.list(projectCode, pageId, status));
    }

    @PatchMapping("/findings/{findingId}/status")
    public ResponseEntity<FindingView> updateFindingStatus(
            @PathVariable String projectCode,
            @PathVariable Long findingId,
            @RequestBody FindingStatusRequest request) {
        return ResponseEntity.ok(pageAnalysis.updateStatus(
                projectCode,
                findingId,
                request == null ? null : request.status(),
                request == null ? null : request.reviewedBy()));
    }

    @GetMapping("/published")
    public ResponseEntity<List<PublishedWorkflowView>> published(
            @PathVariable String projectCode,
            @RequestParam(required = false) String pageKey) {
        return ResponseEntity.ok(publishedService.list(projectCode, pageKey));
    }

    /**
     * Delivery publishes the Workflow and, with an Agent, a new live Agent config, so the caller needs the same
     * publish permissions on the project as the Workflow and Agent pages ask for. The publisher is the session account.
     */
    @PostMapping("/workflow-engineering/tasks/{taskId}/deliver")
    public ResponseEntity<WorkflowDeliveryView> deliverWorkflow(
            HttpServletRequest request,
            @PathVariable String projectCode,
            @PathVariable String taskId,
            @RequestBody DeliveryCommand command) {
        PlatformAuthenticatedSession session = requireDeliveryPermission(request, projectCode, command);
        DeliveryCommand attributed = new DeliveryCommand(command.pageKey(), command.version(), command.agentId(),
                command.modelInstanceId(), session.user().getUsername(), command.replaceWorkflowId());
        return ResponseEntity.ok(workflowDeliveryService.deliver(
                projectCode,
                taskId,
                attributed));
    }

    static PlatformAuthenticatedSession requireDeliveryPermission(HttpServletRequest request, String projectCode, DeliveryCommand command) {
        Object candidate = request == null ? null : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (!(candidate instanceof PlatformAuthenticatedSession session) || session.user() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "live ReachAI platform login is required");
        }
        if (command == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflow delivery command is required");
        boolean allowed = session.hasResourcePermission(PlatformPermissions.WORKFLOW_PUBLISH, "PROJECT", null, projectCode)
                && (command.agentId() == null || command.agentId().isBlank()
                        || session.hasResourcePermission(PlatformPermissions.AGENT_PUBLISH, "PROJECT", null, projectCode));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workflow and Agent publish permission is required");
        return session;
    }

    public record FindingStatusRequest(String status, String reviewedBy) {
    }

}
