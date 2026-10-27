package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.employee.McpWriteApprovalService;
import com.enterprise.ai.control.mcp.application.employee.McpWriteApprovalService.ApprovalView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 写操作审批: write calls from employees' own AI tools waiting for an approver. */
@RestController
@RequestMapping("/api/mcp/write-approvals")
public class McpWriteApprovalController {

    private final McpWriteApprovalService service;
    private final McpHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    public McpWriteApprovalController(McpWriteApprovalService service,
                                      McpHubManagementAccess access,
                                      PlatformAuthAuditService auditService) {
        this.service = service;
        this.access = access;
        this.auditService = auditService;
    }

    @GetMapping
    public McpWriteApprovalService.Page list(HttpServletRequest request,
                                             @RequestParam(required = false) String projectCode,
                                             @RequestParam(required = false) String state,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) Integer offset) {
        PlatformAuthenticatedSession session = access.require(request, McpHubManagementAccess.READ);
        int safeLimit = Math.min(Math.max(limit == null ? 20 : limit, 1), 200);
        return service.list(projectCode, state, keyword, safeLimit, Math.max(offset == null ? 0 : offset, 0),
                seesArguments(session));
    }

    @GetMapping("/{id}")
    public ApprovalView get(HttpServletRequest request, @PathVariable long id) {
        PlatformAuthenticatedSession session = access.require(request, McpHubManagementAccess.READ);
        return service.get(id, seesArguments(session));
    }

    @PostMapping("/{id}/approve")
    public ApprovalView approve(HttpServletRequest request, @PathVariable long id,
                                @RequestBody(required = false) DecisionRequest body) {
        PlatformAuthenticatedSession session = access.require(request, McpHubManagementAccess.MANAGE_WRITE_APPROVALS);
        ApprovalView decided = service.approve(id, access.actor(session), body == null ? null : body.comment());
        auditService.record(session, "MCP_WRITE_APPROVAL_APPROVED", "MCP_WRITE_APPROVAL", String.valueOf(id),
                Map.of("toolName", decided.toolName(), "state", decided.state()));
        return decided;
    }

    @PostMapping("/{id}/reject")
    public ApprovalView reject(HttpServletRequest request, @PathVariable long id,
                               @RequestBody(required = false) DecisionRequest body) {
        PlatformAuthenticatedSession session = access.require(request, McpHubManagementAccess.MANAGE_WRITE_APPROVALS);
        ApprovalView decided = service.reject(id, access.actor(session), body == null ? null : body.comment());
        auditService.record(session, "MCP_WRITE_APPROVAL_REJECTED", "MCP_WRITE_APPROVAL", String.valueOf(id),
                Map.of("toolName", decided.toolName()));
        return decided;
    }

    /** Approvers must see what they approve; other readers only with the payload permission. */
    private static boolean seesArguments(PlatformAuthenticatedSession session) {
        var permissions = session.permissions();
        return permissions != null && (permissions.contains("*")
                || permissions.contains(McpHubManagementAccess.MANAGE_WRITE_APPROVALS)
                || permissions.contains(McpHubManagementAccess.READ_PAYLOAD));
    }

    public record DecisionRequest(String comment) {
    }
}
