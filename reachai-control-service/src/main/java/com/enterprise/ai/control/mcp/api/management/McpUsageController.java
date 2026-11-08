package com.enterprise.ai.control.mcp.api.management;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.mcp.application.usage.AiAuditExportService;
import com.enterprise.ai.control.mcp.application.usage.AiCostService;
import com.enterprise.ai.control.mcp.application.usage.AiUsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final AiCostService costService;
    private final AiAuditExportService auditExportService;
    private final PlatformAuthAuditService authAuditService;

    public McpUsageController(AiUsageService service, McpHubManagementAccess access, AiCostService costService,
                              AiAuditExportService auditExportService, PlatformAuthAuditService authAuditService) {
        this.service = service;
        this.access = access;
        this.costService = costService;
        this.auditExportService = auditExportService;
        this.authAuditService = authAuditService;
    }

    /**
     * 导出审计记录（CSV，Excel 可直接打开）。不含请求、回答和参数内容；导出这件事本身也记一条审计。
     * 登录记录不属于某个业务系统，只有能看全平台的人在不选系统时才导出。
     */
    @GetMapping("/audit-export")
    public ResponseEntity<byte[]> auditExport(HttpServletRequest request,
                                              @RequestParam(required = false) Integer days,
                                              @RequestParam(required = false) String projectCode) {
        PlatformAuthenticatedSession session = access.require(request, McpHubManagementAccess.READ);
        boolean platformWide = session.permissions().contains("*")
                || session.hasGlobalPermission(McpHubManagementAccess.READ);
        if (!platformWide && !StringUtils.hasText(projectCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先选择要导出的业务系统");
        }
        AiAuditExportService.Export export = auditExportService.export(days, projectCode,
                readsRuns(session, projectCode), platformWide);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("days", export.days());
        details.put("projectCode", export.projectCode() == null ? "ALL" : export.projectCode());
        details.put("rows", export.rows());
        details.put("truncated", export.truncated());
        authAuditService.record(session, "AI_USAGE_AUDIT_EXPORTED", "AI_USAGE",
                export.projectCode() == null ? "ALL" : export.projectCode(), details);
        String name = "AI审计记录-" + (export.projectCode() == null ? "全部系统" : export.projectCode()) + "-近"
                + export.days() + "天-" + LocalDate.now() + ".csv";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-audit.csv\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Audit-Rows", String.valueOf(export.rows()))
                .header("X-Audit-Truncated", String.valueOf(export.truncated()))
                .header("Access-Control-Expose-Headers", "Content-Disposition, X-Audit-Rows, X-Audit-Truncated")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(export.csv());
    }

    /** 费用：ReachAI 自己调模型花的 Token 和估算金额；数据来自运行记录，所以同样要能看运行记录。 */
    @GetMapping("/cost")
    public AiCostService.CostReport cost(HttpServletRequest request,
                                         @RequestParam(required = false) Integer days,
                                         @RequestParam(required = false) String projectCode) {
        PlatformAuthenticatedSession session = access.require(request, McpHubManagementAccess.READ);
        return costService.report(days, projectCode, readsRuns(session, projectCode));
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
