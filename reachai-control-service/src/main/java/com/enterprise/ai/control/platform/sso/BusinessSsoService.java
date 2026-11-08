package com.enterprise.ai.control.platform.sso;

import com.enterprise.ai.control.identity.sso.BusinessSsoProvider;
import com.enterprise.ai.control.identity.sso.BusinessSsoProviderStore;
import com.enterprise.ai.control.identity.sso.BusinessSsoSecretCipher;

import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.AttestedBusinessUser;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessUserDisabledException;
import com.enterprise.ai.control.identity.BusinessUserSyncService.SyncedBusinessUser;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformExternalLoginService;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.platform.EmployeePortalSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「用业务系统账号登录」: the OAuth2 authorization code flow against a business system's own login (芋道's
 * OAuth2 server with auto-approve, or any OIDC provider). The employee never sees a consent page.
 *
 * <ul>
 *   <li>An account an administrator bound to a platform user (source provider = this entry's code, external
 *       subject = the business-system user id) gets a console session and lands in the console.</li>
 *   <li>Everyone else is an employee: they appear in the business user directory (roles left as last synced) and
 *       land on the personal page 「我的」 through the same one-time ticket the chat panel uses.</li>
 * </ul>
 * The state lives in memory for five minutes and is single use; a Control restart means clicking the button again.
 */
@Slf4j
@Service
public class BusinessSsoService {

    static final Duration STATE_TTL = Duration.ofMinutes(5);
    static final String CALLBACK_PATH = "/api/platform/auth/sso/callback";
    private static final int MAX_STATES = 10_000;

    private final BusinessSsoProviderStore store;
    private final BusinessSsoSecretCipher cipher;
    private final BusinessSsoHttp http;
    private final BusinessUserSyncService userSync;
    private final EmployeePortalSessionService portalSessions;
    private final PlatformExternalLoginService consoleLogins;
    private final PlatformAuthAuditService audit;
    private final Clock clock;
    private final Map<String, PendingLogin> states = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessSsoService(BusinessSsoProviderStore store, BusinessSsoSecretCipher cipher, BusinessSsoHttp http,
                              BusinessUserSyncService userSync, EmployeePortalSessionService portalSessions,
                              PlatformExternalLoginService consoleLogins, PlatformAuthAuditService audit) {
        this(store, cipher, http, userSync, portalSessions, consoleLogins, audit, Clock.systemUTC());
    }

    BusinessSsoService(BusinessSsoProviderStore store, BusinessSsoSecretCipher cipher, BusinessSsoHttp http,
                       BusinessUserSyncService userSync, EmployeePortalSessionService portalSessions,
                       PlatformExternalLoginService consoleLogins, PlatformAuthAuditService audit, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.http = http;
        this.userSync = userSync;
        this.portalSessions = portalSessions;
        this.consoleLogins = consoleLogins;
        this.audit = audit;
        this.clock = clock;
    }

    /** Buttons for the login page: active entries that are completely configured. */
    public List<LoginButton> buttons() {
        return store.list().stream().filter(this::usable)
                .map(provider -> new LoginButton(provider.code(), provider.label()))
                .toList();
    }

    /** The business system's login URL to send the browser to. */
    public String start(String code, String origin) {
        BusinessSsoProvider provider = store.find(code).filter(this::usable)
                .orElseThrow(() -> new BusinessSsoException("这个登录方式没有启用"));
        Instant now = clock.instant();
        if (states.size() >= MAX_STATES) {
            states.values().removeIf(pending -> pending.expired(now));
            if (states.size() >= MAX_STATES) throw new BusinessSsoException("登录的人太多了，请稍后再试");
        }
        String state = randomHex();
        String redirectUri = origin + CALLBACK_PATH;
        states.put(state, new PendingLogin(provider.code(), redirectUri, now.plus(STATE_TTL)));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", provider.clientId());
        query.put("redirect_uri", redirectUri);
        query.put("state", state);
        if (StringUtils.hasText(provider.scope())) query.put("scope", provider.scope());
        return provider.authorizeUrl() + (provider.authorizeUrl().contains("?") ? "&" : "?") + encodeQuery(query);
    }

    /** Finishes the sign-in. Never throws: a failure becomes a redirect back to the login page with the reason. */
    public Outcome callback(String code, String state, String error, HttpServletRequest request) {
        PendingLogin pending = state == null ? null : states.remove(state);
        String ip = clientIp(request);
        if (pending == null || pending.expired(clock.instant())) {
            return failed(null, null, null, ip, "登录链接已过期，请回到登录页重新点一次");
        }
        BusinessSsoProvider provider = store.find(pending.providerCode()).filter(this::usable).orElse(null);
        if (provider == null) return failed(pending.providerCode(), null, null, ip, "这个登录方式已经停用");
        if (StringUtils.hasText(error)) return failed(provider.code(), null, null, ip, "业务系统没有同意这次登录");
        if (!StringUtils.hasText(code)) return failed(provider.code(), null, null, ip, "业务系统没有返回登录凭证");
        String externalUserId = null;
        String userName = null;
        try {
            Map<String, Object> userInfo = userInfo(provider, accessToken(provider, code, pending.redirectUri()));
            externalUserId = text(path(userInfo, provider.userIdPath()));
            userName = text(path(userInfo, provider.userNamePath()));
            if (externalUserId == null) {
                throw new BusinessSsoException("业务系统的用户信息里没有「" + provider.userIdPath() + "」，请检查认证源配置");
            }
            Optional<PlatformExternalLoginService.ConsoleLogin> console = consoleLogins.signInBoundAccount(
                    provider.code(), externalUserId, request,
                    details(provider.code(), provider.projectCode(), externalUserId, userName, ip, null));
            return console.isPresent()
                    ? new Outcome("/", console.get().cookies())
                    : employeeLogin(provider, externalUserId, userName, ip);
        } catch (BusinessSsoException failure) {
            return failed(provider.code(), externalUserId, userName, ip, failure.getMessage());
        } catch (PlatformExternalLoginService.BoundAccountDisabledException disabled) {
            return failed(provider.code(), externalUserId, userName, ip, "你的管理端账号已被停用，请联系管理员");
        } catch (BusinessUserDisabledException disabled) {
            return failed(provider.code(), externalUserId, userName, ip, "你的账号在 ReachAI 已被停用，请联系管理员");
        } catch (RuntimeException unexpected) {
            log.warn("[BusinessSso] {} sign-in failed: {}", provider.code(), unexpected.toString());
            return failed(provider.code(), externalUserId, userName, ip, "登录失败，请稍后再试");
        }
    }

    private String accessToken(BusinessSsoProvider provider, String code, String redirectUri) {
        Map<String, String> headers = new LinkedHashMap<>(provider.extraHeaders());
        String secret = cipher.decrypt(provider.clientSecretCipher());
        headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (provider.clientId() + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        Map<String, Object> response = http.postForm(provider.tokenUrl(), form, headers);
        rejectWrappedFailure(response);
        String token = text(path(response, "access_token"));
        if (token == null) token = text(path(response, "data.access_token"));
        if (token == null) {
            String reason = text(firstNonNull(path(response, "error_description"), path(response, "error")));
            throw new BusinessSsoException("业务系统没有发放登录令牌" + (reason == null ? "" : "：" + reason));
        }
        return token;
    }

    private Map<String, Object> userInfo(BusinessSsoProvider provider, String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>(provider.extraHeaders());
        headers.put("Authorization", "Bearer " + accessToken);
        Map<String, Object> response = http.getJson(provider.userInfoUrl(), headers);
        rejectWrappedFailure(response);
        return response;
    }

    /** 芋道 and similar frameworks answer HTTP 200 with {@code {"code": 401, "msg": "..."}} on failure. */
    private static void rejectWrappedFailure(Map<String, Object> response) {
        Object code = response.get("code");
        if (code instanceof Number number && number.intValue() != 0 && number.intValue() != 200
                && !response.containsKey("access_token")) {
            String message = text(response.get("msg"));
            throw new BusinessSsoException("业务系统拒绝登录" + (message == null ? "" : "：" + message));
        }
    }

    private Outcome employeeLogin(BusinessSsoProvider provider, String externalUserId, String userName, String ip) {
        String system = provider.projectCode();
        SyncedBusinessUser synced = userSync.syncKeepingRoles(new AttestedBusinessUser(
                null, system, externalUserId, null, userName, List.of()));
        EmployeeOwner owner = new EmployeeOwner(synced.tenantId(), synced.businessUserId(), system, system,
                synced.externalUserId(), synced.externalUserName(), synced.roles());
        EmployeePortalSessionService.Issued ticket = portalSessions.issueTicket(owner);
        audit.record(null, "EMPLOYEE_SSO_LOGIN_SUCCEEDED", "BUSINESS_USER", String.valueOf(synced.businessUserId()),
                details(provider.code(), system, externalUserId, synced.externalUserName(), ip, null));
        return new Outcome("/my/ai-tools#ticket=" + ticket.value(), List.of());
    }

    private Outcome failed(String providerCode, String externalUserId, String userName, String ip, String reason) {
        try {
            audit.record(null, "BUSINESS_SSO_LOGIN_FAILED", "AUTH_PROVIDER", providerCode,
                    details(providerCode, null, externalUserId, userName, ip, reason));
        } catch (RuntimeException auditFailure) {
            log.warn("[BusinessSso] failed to audit a failed sign-in: {}", auditFailure.toString());
        }
        return new Outcome("/login?sso_error=" + URLEncoder.encode(reason, StandardCharsets.UTF_8), List.of());
    }

    private static Map<String, Object> details(String provider, String projectCode, String externalUserId,
                                               String userName, String ip, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (provider != null) details.put("provider", provider);
        if (projectCode != null) details.put("projectCode", projectCode);
        if (externalUserId != null) details.put("externalUserId", externalUserId);
        if (userName != null) details.put("userName", userName);
        if (ip != null) details.put("ip", ip);
        if (reason != null) details.put("reason", reason);
        return details;
    }

    boolean usable(BusinessSsoProvider provider) {
        return provider.active() && provider.clientSecretPresent() && cipher.available()
                && StringUtils.hasText(provider.projectCode()) && StringUtils.hasText(provider.clientId())
                && StringUtils.hasText(provider.authorizeUrl()) && StringUtils.hasText(provider.tokenUrl())
                && StringUtils.hasText(provider.userInfoUrl()) && StringUtils.hasText(provider.userIdPath());
    }

    static Object path(Map<String, Object> root, String dotted) {
        if (root == null || !StringUtils.hasText(dotted)) return null;
        Object current = root;
        for (String part : dotted.trim().split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                ? String.valueOf(number.longValue()) : String.valueOf(value);
        return text.isBlank() ? null : text.trim();
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) return forwarded.split(",")[0].trim();
        String real = request.getHeader("X-Real-IP");
        return StringUtils.hasText(real) ? real.trim() : request.getRemoteAddr();
    }

    private static String encodeQuery(Map<String, String> query) {
        List<String> parts = new ArrayList<>();
        query.forEach((key, value) -> parts.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return String.join("&", parts);
    }

    private String randomHex() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record PendingLogin(String providerCode, String redirectUri, Instant expiresAt) {
        boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    public record LoginButton(String code, String label) {
    }

    /** Where to send the browser, and the console session cookies to set (empty for employees and failures). */
    public record Outcome(String location, List<String> cookies) {
    }
}
