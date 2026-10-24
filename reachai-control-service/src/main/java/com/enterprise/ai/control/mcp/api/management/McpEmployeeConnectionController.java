package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionAdminService;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionAdminService.AdminConnectionView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 员工连接: every employee's personal AI-tool connection, for administrators to review and disconnect. */
@RestController
@RequestMapping("/api/mcp/employee-connections")
public class McpEmployeeConnectionController {

    private final AiToolConnectionAdminService service;
    private final McpHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    public McpEmployeeConnectionController(AiToolConnectionAdminService service,
                                           McpHubManagementAccess access,
                                           PlatformAuthAuditService auditService) {
        this.service = service;
        this.access = access;
        this.auditService = auditService;
    }

    @GetMapping
    public AiToolConnectionAdminService.Page list(HttpServletRequest request,
                                                  @RequestParam(required = false) String projectCode,
                                                  @RequestParam(required = false) String state,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) Integer offset) {
        access.require(request, McpHubManagementAccess.READ);
        int safeLimit = Math.min(Math.max(limit == null ? 20 : limit, 1), 200);
        return service.list(projectCode, state, keyword, safeLimit, Math.max(offset == null ? 0 : offset, 0));
    }

    @PostMapping("/{id}/revoke")
    public AdminConnectionView revoke(HttpServletRequest request, @PathVariable long id) {
        PlatformAuthenticatedSession session = access.requireAny(request,
                McpHubManagementAccess.MANAGE_EMPLOYEE_CONNECTIONS, McpHubManagementAccess.MANAGE_CREDENTIALS);
        AdminConnectionView revoked = service.revoke(id);
        auditService.record(session, "MCP_EMPLOYEE_CONNECTION_REVOKED", "AI_TOOL_CONNECTION", String.valueOf(id),
                Map.of("externalUserId", String.valueOf(revoked.externalUserId()),
                        "toolKind", revoked.toolKind()));
        return revoked;
    }
}
