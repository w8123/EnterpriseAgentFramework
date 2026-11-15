package com.enterprise.ai.control.activity;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.mcp.api.management.McpHubManagementAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 使用情况 ·「用户操作」: what employees do inside connected business systems. Reading follows the usage page
 * (mcp-hub:read); changing what is collected or recommended to employees needs a manager permission.
 */
@RestController
@RequestMapping("/api/mcp/usage/activity")
public class ActivityConsoleController {

    private final ActivityService service;
    private final ActivityInsightService insightService;
    private final McpHubManagementAccess access;
    private final PlatformAuthAuditService auditService;

    public ActivityConsoleController(ActivityService service, ActivityInsightService insightService,
                                     McpHubManagementAccess access, PlatformAuthAuditService auditService) {
        this.service = service;
        this.insightService = insightService;
        this.access = access;
        this.auditService = auditService;
    }

    @GetMapping
    public ActivityService.Report report(HttpServletRequest request,
                                         @RequestParam(required = false) Integer days,
                                         @RequestParam(required = false) String projectCode) {
        access.require(request, McpHubManagementAccess.READ);
        return service.report(days, projectCode);
    }

    @GetMapping("/settings")
    public ActivityService.Setting setting(HttpServletRequest request, @RequestParam String projectCode) {
        access.require(request, McpHubManagementAccess.READ);
        return service.setting(project(projectCode));
    }

    @PutMapping("/settings")
    public ActivityService.Setting updateSetting(HttpServletRequest request, @RequestParam String projectCode,
                                                 @RequestBody ActivityService.SettingCommand command) {
        PlatformAuthenticatedSession session = manager(request);
        String project = project(projectCode);
        ActivityService.Setting updated = service.updateSetting(project, command, access.actor(session));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", updated.enabled());
        details.put("captureSearch", updated.captureSearch());
        details.put("hideUserNames", updated.hideUserNames());
        details.put("retentionDays", updated.retentionDays());
        auditService.record(session, "ACTIVITY_SETTING_UPDATED", "ACTIVITY_SETTING", project, details);
        return updated;
    }

    @GetMapping("/insights")
    public List<ActivityInsightService.InsightView> insights(HttpServletRequest request,
                                                             @RequestParam String projectCode) {
        access.require(request, McpHubManagementAccess.READ);
        return insightService.list(project(projectCode));
    }

    @PostMapping("/insights/generate")
    public ActivityInsightService.Generation generate(HttpServletRequest request, @RequestParam String projectCode,
                                                      @RequestBody(required = false) GenerateCommand command) {
        PlatformAuthenticatedSession session = manager(request);
        String project = project(projectCode);
        ActivityInsightService.Generation generation = insightService.generate(project,
                command == null ? null : command.days(),
                command == null ? null : command.modelInstanceId(),
                access.actor(session));
        auditService.record(session, "ACTIVITY_INSIGHTS_GENERATED", "ACTIVITY_INSIGHT", project,
                Map.of("days", generation.days(), "events", generation.events(), "ai", generation.ai()));
        return generation;
    }

    @PutMapping("/insights/{id}")
    public ActivityInsightService.InsightView updateInsight(HttpServletRequest request, @RequestParam String projectCode,
                                                            @PathVariable Long id,
                                                            @RequestBody ActivityInsightService.InsightCommand command) {
        PlatformAuthenticatedSession session = manager(request);
        String project = project(projectCode);
        ActivityInsightService.InsightView view = insightService.update(project, id, command, access.actor(session));
        auditService.record(session, "ACTIVITY_INSIGHT_UPDATED", "ACTIVITY_INSIGHT", String.valueOf(id),
                Map.of("projectCode", project, "status", view.status()));
        return view;
    }

    private PlatformAuthenticatedSession manager(HttpServletRequest request) {
        return access.requireAny(request, McpHubManagementAccess.MANAGE_WRITE_APPROVALS,
                McpHubManagementAccess.MANAGE_EMPLOYEE_CONNECTIONS);
    }

    private static String project(String projectCode) {
        if (!StringUtils.hasText(projectCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先选择业务系统");
        }
        return ActivityIngressController.normalizeProjectCode(projectCode);
    }

    public record GenerateCommand(Integer days, String modelInstanceId) {
    }
}
