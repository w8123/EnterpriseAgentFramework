package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.agentskill.AgentSkillEmployeeCatalog;
import com.enterprise.ai.control.agentskill.AgentSkillEmployeeCatalog.EmployeeSkill;
import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.mcp.application.calllog.McpCallLogReader;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.ConnectionView;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.SystemOption;
import com.enterprise.ai.control.mcp.application.employee.AiToolSetupInstructions;
import com.enterprise.ai.control.mcp.application.employee.McpWriteApprovalService;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.enterprise.ai.control.platform.EmbedAiToolConnectionController.CreateConnectionRequest;
import com.enterprise.ai.control.platform.EmbedAiToolConnectionController.CreatedConnectionResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The employee personal page (「我的 AI 工具」) opened in an ordinary browser tab.
 * It signs in with a single-use ticket from the chat panel, so the employee never
 * types a password, and then offers the same connection management as the panel
 * plus the employee's own recent calls. Paths under ai-tool-connections mirror
 * /api/embed/ai-tool-connections so the panel component works unchanged.
 */
@RestController
@RequestMapping("/api/employee-portal")
public class EmployeePortalController {

    static final int RECENT_CALL_LIMIT = 50;

    private final EmployeePortalSessionService sessions;
    private final AiToolConnectionService connectionService;
    private final McpWriteApprovalService approvalService;
    private final McpCallLogReader callLogReader;
    private final McpHubProperties properties;
    private final AgentSkillEmployeeCatalog skillCatalog;

    public EmployeePortalController(EmployeePortalSessionService sessions,
                                    AiToolConnectionService connectionService,
                                    McpWriteApprovalService approvalService,
                                    McpCallLogReader callLogReader,
                                    McpHubProperties properties,
                                    AgentSkillEmployeeCatalog skillCatalog) {
        this.sessions = sessions;
        this.connectionService = connectionService;
        this.approvalService = approvalService;
        this.callLogReader = callLogReader;
        this.properties = properties;
        this.skillCatalog = skillCatalog;
    }

    /** 「可用的做法」, same as the chat panel: published Skills of the systems this employee can connect. */
    @GetMapping("/ai-tool-connections/skills")
    public ResponseEntity<ApiResult<List<EmployeeSkill>>> skills(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, owner -> skillCatalog.list(systems(owner)));
    }

    @GetMapping("/ai-tool-connections/skills/{skillId}/package")
    public ResponseEntity<?> skillPackage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long skillId) {
        EmployeeOwner owner = sessions.resolve(bearer(authorization)).orElse(null);
        if (owner == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401,
                    "登录已过期，请回到业务系统的 AI 对话面板重新打开"));
        }
        AgentSkillEmployeeCatalog.Package pkg;
        try {
            pkg = skillCatalog.packageFor(skillId, systems(owner));
        } catch (AgentSkillException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResult.fail(404, "这个做法不存在或已下线"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pkg.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-ReachAI-Skill-SHA256", pkg.sha256())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(pkg.body().length)
                .body(pkg.body());
    }

    private Map<String, String> systems(EmployeeOwner owner) {
        Map<String, String> systems = new LinkedHashMap<>();
        for (SystemOption option : connectionService.options(owner)) {
            systems.put(option.projectCode(), option.name());
        }
        return systems;
    }

    @PostMapping("/session")
    public ResponseEntity<ApiResult<SessionResponse>> signIn(@RequestBody(required = false) SignInRequest request) {
        return sessions.redeem(request == null ? null : request.ticket())
                .map(session -> ResponseEntity.ok(ApiResult.ok(new SessionResponse(
                        session.value(), session.expiresAt().toString(), me(session.owner())))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401,
                        "链接已失效，请回到业务系统的 AI 对话面板重新打开")));
    }

    @DeleteMapping("/session")
    public ResponseEntity<ApiResult<Boolean>> signOut(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        sessions.end(bearer(authorization));
        return ResponseEntity.ok(ApiResult.ok(true));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResult<Me>> profile(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, this::me);
    }

    /** The employee's own recent calls, across all of their connections. Arguments and results are not shown. */
    @GetMapping("/calls")
    public ResponseEntity<ApiResult<List<CallView>>> calls(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, owner -> {
            List<ConnectionView> connections = connectionService.list(owner);
            Map<Long, String> tools = new HashMap<>();
            Map<String, String> systemNames = new HashMap<>();
            for (ConnectionView connection : connections) {
                tools.put(connection.id(), connection.toolKind());
                connection.systems().forEach(system -> systemNames.putIfAbsent(system.projectCode(), system.name()));
            }
            return callLogReader.recentByConnections(List.copyOf(tools.keySet()), RECENT_CALL_LIMIT).stream()
                    .map(entry -> new CallView(entry.id(), tools.get(entry.connectionId()), entry.toolName(),
                            systemNames.getOrDefault(entry.projectCode(), entry.projectCode()),
                            Boolean.TRUE.equals(entry.success()), entry.latencyMs(), entry.errorCategory(),
                            entry.createdAt()))
                    .toList();
        });
    }

    @GetMapping("/ai-tool-connections/approvals")
    public ResponseEntity<ApiResult<List<Map<String, Object>>>> approvals(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, approvalService::mine);
    }

    @GetMapping("/ai-tool-connections/options")
    public ResponseEntity<ApiResult<List<SystemOption>>> options(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, connectionService::options);
    }

    @GetMapping("/ai-tool-connections")
    public ResponseEntity<ApiResult<List<ConnectionView>>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, connectionService::list);
    }

    @PostMapping("/ai-tool-connections")
    public ResponseEntity<ApiResult<CreatedConnectionResponse>> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) CreateConnectionRequest request,
            HttpServletRequest httpRequest) {
        return handle(authorization, owner -> {
            var created = connectionService.create(owner,
                    request == null ? null : request.toolKind(),
                    request == null ? null : request.systems());
            AiToolKind kind = AiToolKind.valueOf(created.connection().toolKind());
            return new CreatedConnectionResponse(created.connection(),
                    AiToolSetupInstructions.forTool(kind, PlatformPublicOrigin.resolve(properties, httpRequest),
                            created.secret()));
        });
    }

    @DeleteMapping("/ai-tool-connections/{id}")
    public ResponseEntity<ApiResult<ConnectionView>> revoke(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id) {
        return handle(authorization, owner -> connectionService.revoke(owner, id));
    }

    private Me me(EmployeeOwner owner) {
        String systemName = connectionService.options(owner).stream()
                .filter(SystemOption::current)
                .map(SystemOption::name)
                .findFirst()
                .orElse(owner.projectCode());
        return new Me(owner.userName(), owner.externalUserId(), owner.projectCode(), systemName);
    }

    private <T> ResponseEntity<ApiResult<T>> handle(String authorization, Function<EmployeeOwner, T> action) {
        EmployeeOwner owner = sessions.resolve(bearer(authorization)).orElse(null);
        if (owner == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401,
                    "登录已过期，请回到业务系统的 AI 对话面板重新打开"));
        }
        try {
            return ResponseEntity.ok(ApiResult.ok(action.apply(owner)));
        } catch (McpDomainException ex) {
            HttpStatus status = ex.code().endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResult.fail(status.value(), ex.getMessage()));
        }
    }

    private static String bearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    public record SignInRequest(String ticket) {
    }

    public record SessionResponse(String token, String expiresAt, Me me) {
    }

    /** Who is signed in: the business-system account the chat panel vouched for. */
    public record Me(String userName, String account, String projectCode, String systemName) {
    }

    public record CallView(Long id, String toolKind, String toolName, String businessSystem, boolean success,
                           Long latencyMs, String errorCategory, LocalDateTime createdAt) {
    }
}
