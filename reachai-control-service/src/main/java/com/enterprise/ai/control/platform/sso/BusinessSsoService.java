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
    static final int MAX_PASSWORD_FAILURES = 5;
    static final Duration PASSWORD_LOCK = Duration.ofMinutes(10);

    private final BusinessSsoProviderStore store;
    private final BusinessSsoSecretCipher cipher;
    private final BusinessSsoHttp http;
    private final BusinessUserSyncService userSync;
    private final EmployeePortalSessionService portalSessions;
    private final PlatformExternalLoginService consoleLogins;
    private final PlatformAuthAuditService audit;
    private final EmployeeBusinessTokens employeeTokens;
    private final Clock clock;
    private final Map<String, PendingLogin> states = new ConcurrentHashMap<>();
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessSsoService(BusinessSsoProviderStore store, BusinessSsoSecretCipher cipher, BusinessSsoHttp http,
                              BusinessUserSyncService userSync, EmployeePortalSessionService portalSessions,
                              PlatformExternalLoginService consoleLogins, PlatformAuthAuditService audit,
                              EmployeeBusinessTokens employeeTokens) {
        this(store, cipher, http, userSync, portalSessions, consoleLogins, audit, employeeTokens, Clock.systemUTC());
    }

    BusinessSsoService(BusinessSsoProviderStore store, BusinessSsoSecretCipher cipher, BusinessSsoHttp http,
                       BusinessUserSyncService userSync, EmployeePortalSessionService portalSessions,
                       PlatformExternalLoginService consoleLogins, PlatformAuthAuditService audit,
                       EmployeeBusinessTokens employeeTokens, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.http = http;
        this.userSync = userSync;
        this.portalSessions = portalSessions;
        this.consoleLogins = consoleLogins;
        this.audit = audit;
        this.employeeTokens = employeeTokens;
        this.clock = clock;
    }

    /** Buttons for the login page: active entries that are completely configured. */
    public List<LoginButton> buttons() {
        return store.list().stream().filter(this::usable)
                .map(provider -> new LoginButton(provider.code(), provider.label(), provider.mode()))
                .toList();
    }

    /** The business system's login URL to send the browser to. */
    public String start(String code, String origin) {
        BusinessSsoProvider provider = store.find(code).filter(this::usable)
                .orElseThrow(() -> new BusinessSsoException("这个登录方式没有启用"));
        if (provider.passwordMode()) throw new BusinessSsoException("这个登录方式需要在登录页输入账号和密码");
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
            String accessToken = accessToken(provider, code, pending.redirectUri());
            Map<String, Object> userInfo = userInfo(provider, accessToken);
            externalUserId = text(path(userInfo, provider.userIdPath()));
            userName = text(path(userInfo, provider.userNamePath()));
            if (externalUserId == null) {
                throw new BusinessSsoException("业务系统的用户信息里没有「" + provider.userIdPath() + "」，请检查认证源配置");
            }
            return signedIn(provider, externalUserId, userName, request, ip, accessToken);
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

    private Outcome signedIn(BusinessSsoProvider provider, String externalUserId, String userName,
                             HttpServletRequest request, String ip, String businessToken) {
        Optional<PlatformExternalLoginService.ConsoleLogin> console = consoleLogins.signInBoundAccount(
                provider.code(), externalUserId, request,
                details(provider.code(), provider.projectCode(), externalUserId, userName, ip, null));
        return console.isPresent()
                ? new Outcome("/", console.get().cookies())
                : employeeLogin(provider, externalUserId, userName, ip, businessToken);
    }

    /**
     * 「账号密码验证」: the employee typed their business-system account on ReachAI's login page. ReachAI asks the
     * system's own login API whether it is right and never stores or logs the password. Never throws.
     */
    public PasswordOutcome passwordLogin(String code, String username, String password, HttpServletRequest request) {
        String ip = clientIp(request);
        BusinessSsoProvider provider = store.find(code).filter(this::usable)
                .filter(BusinessSsoProvider::passwordMode).orElse(null);
        if (provider == null) return refused(code, null, ip, "这个登录方式没有启用");
        String account = username == null ? "" : username.trim();
        if (account.isEmpty() || account.length() > 128 || password == null || password.isEmpty()
                || password.length() > 256) {
            return refused(provider.code(), null, ip, "请输入账号和密码");
        }
        Instant now = clock.instant();
        String accountKey = provider.code() + "|" + account.toLowerCase(java.util.Locale.ROOT);
        String ipKey = "ip|" + ip;
        if (locked(accountKey, now) || locked(ipKey, now)) {
            return refused(provider.code(), account, ip, "试错次数太多了，请 10 分钟后再试");
        }
        String externalUserId = null;
        String userName = null;
        try {
            BusinessSsoProvider.PasswordLogin login = provider.passwordLogin();
            boolean form = "FORM".equals(login.contentType());
            Map<String, Object> answer;
            try {
                answer = http.postBody(provider.tokenUrl(),
                        form ? "application/x-www-form-urlencoded" : "application/json",
                        render(login.body(), account, password, form), provider.extraHeaders());
            } catch (BusinessSsoException refusedBySystem) {
                // Most systems answer a wrong password with 400 / 401 / 403.
                if (refusedBySystem.getMessage().matches(".*HTTP 40[013].*")) {
                    throw new BusinessSsoException("账号或密码不对");
                }
                throw refusedBySystem;
            }
            rejectWrappedFailure(answer);
            // Only a login token in the answer proves the password was right; "no error" is not proof.
            Object rawToken = path(answer, login.tokenPath());
            String token = rawToken instanceof String || rawToken instanceof Number ? text(rawToken) : null;
            if (token == null) throw new BusinessSsoException("账号或密码不对");
            Map<String, Object> identity = answer;
            if (StringUtils.hasText(provider.userInfoUrl())) {
                String header = StringUtils.hasText(login.tokenHeader()) ? login.tokenHeader().trim() : "Authorization";
                String prefix = login.tokenPrefix() != null ? login.tokenPrefix()
                        : "Authorization".equalsIgnoreCase(header) ? "Bearer " : "";
                Map<String, String> headers = new LinkedHashMap<>(provider.extraHeaders());
                headers.put(header, prefix + token);
                identity = http.getJson(provider.userInfoUrl(), headers);
                rejectWrappedFailure(identity);
            }
            // With no id field configured the login name is the account id: it is what the system checked.
            externalUserId = StringUtils.hasText(provider.userIdPath())
                    ? text(path(identity, provider.userIdPath())) : account;
            if (externalUserId == null) {
                throw new BusinessSsoException("业务系统的返回里没有「" + provider.userIdPath() + "」，请检查认证源配置");
            }
            userName = text(path(identity, provider.userNamePath()));
            if (userName == null) userName = account;
            Outcome outcome = signedIn(provider, externalUserId, userName, request, ip, token);
            failures.remove(accountKey);
            return new PasswordOutcome(outcome.location(), outcome.cookies(), null);
        } catch (BusinessSsoException failure) {
            failed(accountKey, now);
            failed(ipKey, now);
            return refused(provider.code(), account, ip, failure.getMessage());
        } catch (PlatformExternalLoginService.BoundAccountDisabledException disabled) {
            return refused(provider.code(), account, ip, "你的管理端账号已被停用，请联系管理员");
        } catch (BusinessUserDisabledException disabled) {
            return refused(provider.code(), account, ip, "你的账号在 ReachAI 已被停用，请联系管理员");
        } catch (RuntimeException unexpected) {
            log.warn("[BusinessSso] {} password sign-in failed: {}", provider.code(), unexpected.getClass().getName());
            return refused(provider.code(), account, ip, "登录失败，请稍后再试");
        }
    }

    private PasswordOutcome refused(String providerCode, String account, String ip, String reason) {
        failed(providerCode, null, account, ip, reason);
        return new PasswordOutcome(null, List.of(), reason);
    }

    private boolean locked(String key, Instant now) {
        Attempts attempts = failures.get(key);
        if (attempts == null) return false;
        if (!now.isBefore(attempts.resetAt())) {
            failures.remove(key);
            return false;
        }
        return attempts.count() >= MAX_PASSWORD_FAILURES;
    }

    private void failed(String key, Instant now) {
        if (failures.size() >= MAX_STATES) failures.values().removeIf(attempts -> !now.isBefore(attempts.resetAt()));
        if (failures.size() >= MAX_STATES) return;
        failures.merge(key, new Attempts(1, now.plus(PASSWORD_LOCK)), (old, fresh) ->
                !now.isBefore(old.resetAt()) ? fresh : new Attempts(old.count() + 1, old.resetAt()));
    }

    /** Fills the login template; every value is escaped for the body it goes into. */
    static String render(String template, String username, String password, boolean form) {
        String md5;
        try {
            md5 = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5")
                    .digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return template.replace("{{username}}", escape(username, form))
                .replace("{{password_md5}}", md5)
                .replace("{{password}}", escape(password, form));
    }

    private static String escape(String value, boolean form) {
        if (form) return URLEncoder.encode(value, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.toString();
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

    private Outcome employeeLogin(BusinessSsoProvider provider, String externalUserId, String userName, String ip,
                                  String businessToken) {
        String system = provider.projectCode();
        SyncedBusinessUser synced = userSync.syncKeepingRoles(new AttestedBusinessUser(
                null, system, externalUserId, null, userName, List.of()));
        if (synced.roles().isEmpty() && StringUtils.hasText(provider.defaultRole())) {
            // Nobody has given this employee a role in the system yet: start them on the entry's default role.
            synced = userSync.sync(new AttestedBusinessUser(
                    null, system, externalUserId, null, userName, List.of(provider.defaultRole())));
        }
        EmployeeOwner owner = new EmployeeOwner(synced.tenantId(), synced.businessUserId(), system, system,
                synced.externalUserId(), synced.externalUserName(), synced.roles());
        EmployeePortalSessionService.Issued ticket = portalSessions.issueTicket(owner);
        // Kept in memory only, and only when this entry is set to call the system as the employee.
        if (employeeTokens != null) {
            employeeTokens.signedIn(provider, synced.tenantId(), synced.externalUserId(), businessToken);
        }
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
        if (provider.passwordMode()) {
            String body = provider.passwordLogin().body();
            return provider.active() && StringUtils.hasText(provider.projectCode())
                    && StringUtils.hasText(provider.tokenUrl()) && StringUtils.hasText(body)
                    && body.contains("{{username}}") && body.contains("{{password")
                    && StringUtils.hasText(provider.passwordLogin().tokenPath());
        }
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

    public record LoginButton(String code, String label, String mode) {
    }

    /** Where to go after a password sign-in, or why it was refused. */
    public record PasswordOutcome(String location, List<String> cookies, String error) {
    }

    private record Attempts(int count, Instant resetAt) {
    }

    /** Where to send the browser, and the console session cookies to set (empty for employees and failures). */
    public record Outcome(String location, List<String> cookies) {
    }
}
