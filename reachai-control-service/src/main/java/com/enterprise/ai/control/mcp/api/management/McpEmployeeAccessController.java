package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeAccessService;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 「员工可用」: lets employees of one business system use this publication from their own AI tools. */
@RestController
@RequestMapping("/api/mcp/publications/{publicationId}/employee-access")
public class McpEmployeeAccessController {

    private final McpEmployeeAccessService service;
    private final McpHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    public McpEmployeeAccessController(McpEmployeeAccessService service,
                                       McpHubManagementAccess access,
                                       PlatformAuthAuditService auditService) {
        this.service = service;
        this.access = access;
        this.auditService = auditService;
    }

    @GetMapping
    public EmployeeAccessView get(HttpServletRequest request, @PathVariable long publicationId) {
        access.require(request, McpHubManagementAccess.READ);
        return service.find(publicationId).map(EmployeeAccessView::of)
                .orElse(new EmployeeAccessView(publicationId, null, null, null, false, true));
    }

    @PutMapping
    public EmployeeAccessView save(HttpServletRequest request, @PathVariable long publicationId,
                                   @RequestBody SaveRequest body) {
        PlatformAuthenticatedSession session = access.require(request, McpHubManagementAccess.MANAGE_PUBLICATIONS);
        McpEmployeeAccess saved = service.save(publicationId, body.projectId(), body.projectCode(), body.displayName(),
                body.enabled() == null || body.enabled(), body.writeApprovalRequired());
        auditService.record(session, "MCP_EMPLOYEE_ACCESS_SAVED", "MCP_PUBLICATION",
                String.valueOf(publicationId),
                Map.of("projectCode", saved.projectCode(), "enabled", saved.enabled(),
                        "writeApprovalRequired", saved.writeApprovalRequired()));
        return EmployeeAccessView.of(saved);
    }

    public record SaveRequest(Long projectId, String projectCode, String displayName, Boolean enabled,
                              Boolean writeApprovalRequired) {
    }

    public record EmployeeAccessView(long publicationId, Long projectId, String projectCode, String displayName,
                                     boolean enabled, boolean writeApprovalRequired) {
        static EmployeeAccessView of(McpEmployeeAccess access) {
            return new EmployeeAccessView(access.publicationId(), access.projectId(), access.projectCode(),
                    access.displayName(), access.enabled(), access.writeApprovalRequired());
        }
    }
}
