package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.calllog.McpCallLogReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/mcp/call-logs")
public class McpCallLogController {

    private final McpCallLogReader callLogReader;
    private final McpHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    public McpCallLogController(McpCallLogReader callLogReader,
                                McpHubManagementAccess access,
                                PlatformAuthAuditService auditService) {
        this.callLogReader = callLogReader;
        this.access = access;
        this.auditService = auditService;
    }

    /** List view is redacted: request/response bodies stay out unless separately fetched. */
    @GetMapping
    public McpCallLogReader.Page list(
            HttpServletRequest request,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Long publicationId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String errorCategory,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        access.require(request, McpHubManagementAccess.READ);
        int safeLimit = Math.min(Math.max(limit == null ? 50 : limit, 1), 500);
        McpCallLogReader.Page page = callLogReader.page(direction, publicationId, clientId, method,
                toolName, success, errorCategory, days, safeLimit,
                Math.max(offset == null ? 0 : offset, 0));
        return new McpCallLogReader.Page(
                page.items().stream().map(McpCallLogReader.Entry::withoutBodies).toList(),
                page.total(), page.limit(), page.offset());
    }

    /** Full bodies are only served to the payload-read permission. */
    @GetMapping("/{id}")
    public McpCallLogReader.Entry detail(HttpServletRequest request, @PathVariable long id) {
        PlatformAuthenticatedSession session =
                access.require(request, McpHubManagementAccess.READ_PAYLOAD);
        McpCallLogReader.Entry entry = callLogReader.detail(id);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "call log not found: " + id);
        }
        auditService.record(session, "MCP_CALL_PAYLOAD_READ", "MCP_CALL_LOG",
                String.valueOf(id), Map.of("direction", entry.direction(),
                        "hasRequestBody", entry.requestBody() != null,
                        "hasResponseBody", entry.responseBody() != null));
        return entry;
    }
}
