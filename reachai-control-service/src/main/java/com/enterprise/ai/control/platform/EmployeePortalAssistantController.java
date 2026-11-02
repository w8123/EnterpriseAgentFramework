package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.platform.EmployeePortalSessionService.ChatGrant;
import com.enterprise.ai.control.platform.EmployeePortalSessionService.Issued;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 员工个人页 · 我的助手。
 *
 * <p>Mints a short-lived Embed Token for the full-screen chat on the personal page, so it can use the ordinary
 * embedded chat API. Everything in the token comes from the chat grant recorded when the employee opened the page
 * from the business system's chat panel: the same project, Agent and attested user, never anything from this
 * request. The token never outlives the portal session, and it is bound to the page instance the browser names, as
 * an embedded chat token is.
 */
@RestController
@RequestMapping("/api/employee-portal/assistant")
@RequiredArgsConstructor
public class EmployeePortalAssistantController {

    static final String PORTAL_ROUTE = "/my/ai-tools";
    private static final Pattern PAGE_INSTANCE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final EmployeePortalSessionService sessions;
    private final PlatformEmbedTokenService tokenService;
    private final PlatformEmbedTokenProperties tokenProperties;
    private final McpHubProperties hubProperties;
    private final Clock clock = Clock.systemUTC();

    @PostMapping("/token")
    public ResponseEntity<ApiResult<AssistantToken>> token(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) AssistantTokenRequest request,
            HttpServletRequest httpRequest) {
        Issued session = sessions.session(bearer(authorization)).orElse(null);
        if (session == null) {
            return fail(HttpStatus.UNAUTHORIZED, "登录已过期，请回到业务系统的 AI 对话面板重新打开");
        }
        ChatGrant chat = session.chat();
        if (chat == null || !StringUtils.hasText(chat.agentId()) || !StringUtils.hasText(chat.externalUserId())) {
            return fail(HttpStatus.CONFLICT, "这个页面不是从 AI 对话面板打开的，请在业务系统的 AI 对话面板里点「在浏览器里管理」重新打开");
        }
        String pageInstanceId = request == null || request.pageInstanceId() == null ? "" : request.pageInstanceId().trim();
        if (!PAGE_INSTANCE_ID.matcher(pageInstanceId).matches()) {
            return fail(HttpStatus.BAD_REQUEST, "pageInstanceId is invalid");
        }
        long sessionSecondsLeft = Duration.between(clock.instant(), session.expiresAt()).getSeconds();
        if (sessionSecondsLeft <= 0) {
            return fail(HttpStatus.UNAUTHORIZED, "登录已过期，请回到业务系统的 AI 对话面板重新打开");
        }
        int ttl = (int) Math.min(tokenProperties.getDefaultTokenTtlSeconds(), sessionSecondsLeft);
        PlatformEmbedTokenIssueResult issued = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId(chat.tenantId())
                .appId(chat.appId())
                .projectCode(chat.projectCode())
                .agentId(chat.agentId())
                .pageInstanceId(pageInstanceId)
                .route(PORTAL_ROUTE)
                .origin(PlatformPublicOrigin.resolve(hubProperties, httpRequest))
                .businessSessionId(chat.businessSessionId())
                .businessSessionExpiresAt(chat.sourceExpiresAt() == null ? null : chat.sourceExpiresAt().getEpochSecond())
                .ttlSeconds(ttl)
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                        chat.externalUserId(),
                        chat.globalUserId(),
                        chat.userName(),
                        chat.roles(),
                        chat.attributes()))
                .build());
        return ResponseEntity.ok(ApiResult.ok(new AssistantToken(
                issued.token(), issued.expiresIn(), issued.expiresAt().toString(), chat.agentId())));
    }

    private static <T> ResponseEntity<ApiResult<T>> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResult.fail(status.value(), message));
    }

    private static String bearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    public record AssistantTokenRequest(String pageInstanceId) {
    }

    public record AssistantToken(String token, long expiresIn, String expiresAt, String agentId) {
    }
}
