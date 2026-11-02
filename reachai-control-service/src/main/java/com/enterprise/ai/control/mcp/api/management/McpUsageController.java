package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.usage.AiUsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 使用情况: who used AI, with which tool, what they did and how write requests were decided. */
@RestController
@RequestMapping("/api/mcp/usage")
public class McpUsageController {

    private final AiUsageService service;
    private final McpHubManagementAccess access;

    public McpUsageController(AiUsageService service, McpHubManagementAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public AiUsageService.Report report(HttpServletRequest request,
                                        @RequestParam(required = false) Integer days,
                                        @RequestParam(required = false) String projectCode) {
        PlatformAuthenticatedSession session = access.require(request, McpHubManagementAccess.READ);
        return service.report(days, projectCode, readsRuns(session, projectCode));
    }

    /** The embedded-chat part comes from run records, so it follows runops:read (global, or for that system). */
    static boolean readsRuns(PlatformAuthenticatedSession session, String projectCode) {
        if (session.permissions().contains("*") || session.hasGlobalPermission(PlatformPermissions.RUNOPS_READ)) {
            return true;
        }
        return StringUtils.hasText(projectCode) && session.hasResourcePermission(
                PlatformPermissions.RUNOPS_READ, "PROJECT", null, projectCode.trim());
    }
}
