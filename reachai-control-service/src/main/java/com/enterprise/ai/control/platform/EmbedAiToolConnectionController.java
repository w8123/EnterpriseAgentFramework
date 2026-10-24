package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.AttestedBusinessUser;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessUserDisabledException;
import com.enterprise.ai.control.identity.BusinessUserSyncService.SyncedBusinessUser;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.ConnectionView;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.SystemOption;
import com.enterprise.ai.control.mcp.application.employee.AiToolSetupInstructions;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
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

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 「在我的 AI 工具中使用」 for the employee inside a business system. The employee
 * is the verified Embed Token's user, synced into the ReachAI business-user
 * directory on every call; nothing in the request can name another employee.
 */
@RestController
@RequestMapping("/api/embed/ai-tool-connections")
public class EmbedAiToolConnectionController {

    private final PlatformEmbedTokenService tokenService;
    private final BusinessUserSyncService businessUserSyncService;
    private final AiToolConnectionService connectionService;
    private final McpHubProperties properties;

    public EmbedAiToolConnectionController(PlatformEmbedTokenService tokenService,
                                           BusinessUserSyncService businessUserSyncService,
                                           AiToolConnectionService connectionService,
                                           McpHubProperties properties) {
        this.tokenService = tokenService;
        this.businessUserSyncService = businessUserSyncService;
        this.connectionService = connectionService;
        this.properties = properties;
    }

    @GetMapping("/options")
    public ResponseEntity<ApiResult<List<SystemOption>>> options(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, connectionService::options);
    }

    @GetMapping
    public ResponseEntity<ApiResult<List<ConnectionView>>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return handle(authorization, connectionService::list);
    }

    @PostMapping
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
                    AiToolSetupInstructions.forTool(kind, baseUrl(httpRequest), created.secret()));
        });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<ConnectionView>> revoke(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id) {
        return handle(authorization, owner -> connectionService.revoke(owner, id));
    }

    private <T> ResponseEntity<ApiResult<T>> handle(String authorization, Function<EmployeeOwner, T> action) {
        EmployeeOwner owner;
        try {
            owner = owner(authorization);
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401, ex.getMessage()));
        } catch (BusinessUserDisabledException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, "该账号已被停用"));
        }
        try {
            return ResponseEntity.ok(ApiResult.ok(action.apply(owner)));
        } catch (McpDomainException ex) {
            HttpStatus status = ex.code().endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(ApiResult.fail(status.value(), ex.getMessage()));
        }
    }

    EmployeeOwner owner(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new PlatformEmbedTokenException("Authorization Bearer embed token is required");
        }
        PlatformEmbedTokenClaims claims = tokenService.verify(authorization.substring("Bearer ".length()).trim());
        if (!StringUtils.hasText(claims.getExternalUserId())) {
            throw new PlatformEmbedTokenException("embed token has no business-system user");
        }
        String projectCode = StringUtils.hasText(claims.getProjectCode())
                ? claims.getProjectCode().trim() : claims.getAppId();
        if (!StringUtils.hasText(projectCode)) {
            throw new PlatformEmbedTokenException("embed token has no business system");
        }
        // The ReachAI project code is the business system's id in the user directory, so the
        // account synced here is the one personal connections later resolve for that system.
        String appId = projectCode;
        SyncedBusinessUser synced = businessUserSyncService.sync(new AttestedBusinessUser(
                claims.getTenantId(), appId, claims.getExternalUserId(), claims.getGlobalUserId(),
                claims.getUserName(), claims.getRoles()));
        return new EmployeeOwner(synced.tenantId(), synced.businessUserId(), appId, projectCode,
                synced.externalUserId(), synced.externalUserName(), synced.roles());
    }

    private String baseUrl(HttpServletRequest request) {
        if (StringUtils.hasText(properties.getPublicBaseUrl())) {
            return properties.getPublicBaseUrl().trim();
        }
        String scheme = firstHeader(request, "X-Forwarded-Proto");
        if (!StringUtils.hasText(scheme)) {
            scheme = request.getScheme();
        }
        String host = firstHeader(request, "X-Forwarded-Host");
        if (!StringUtils.hasText(host)) {
            host = firstHeader(request, HttpHeaders.HOST);
        }
        if (!StringUtils.hasText(host)) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return scheme.toLowerCase(Locale.ROOT) + "://" + host;
    }

    private static String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return StringUtils.hasText(value) ? value.split(",")[0].trim() : null;
    }

    public record CreateConnectionRequest(String toolKind, List<String> systems) {
    }

    public record CreatedConnectionResponse(ConnectionView connection, AiToolSetupInstructions.Setup setup) {
    }
}
