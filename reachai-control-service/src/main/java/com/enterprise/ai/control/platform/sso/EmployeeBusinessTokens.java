package com.enterprise.ai.control.platform.sso;

import com.enterprise.ai.control.identity.sso.BusinessSsoProvider;
import com.enterprise.ai.control.identity.sso.BusinessSsoProviderStore;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「用员工自己的令牌调用」: the token a business system gave an employee when they signed in to ReachAI with
 * their business account. Runtime asks for it when it calls that system for that employee, so the system
 * applies the employee's own permissions and records the employee, not a shared account.
 *
 * <p>Tokens live in memory only: never in the database, logs or audit. A restart forgets them and the
 * employee signs in again. Whether a system uses them is the login entry's setting, read on every question,
 * so turning it off takes effect at once.
 */
@Component
public class EmployeeBusinessTokens {

    /** Nobody keeps a token longer than this, even when the business system never says it expired. */
    static final Duration MAX_HOLD = Duration.ofDays(7);
    private static final int MAX_TOKENS = 20_000;

    private final BusinessSsoProviderStore store;
    private final Clock clock;
    private final Map<String, Held> tokens = new ConcurrentHashMap<>();

    @Autowired
    public EmployeeBusinessTokens(BusinessSsoProviderStore store) {
        this(store, Clock.systemUTC());
    }

    EmployeeBusinessTokens(BusinessSsoProviderStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Called right after a successful employee sign-in through {@code provider}. */
    public void signedIn(BusinessSsoProvider provider, String tenantId, String externalUserId, String token) {
        if (provider == null || !provider.usesEmployeeToken() || !StringUtils.hasText(token)
                || token.length() > 4096 || token.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            return;
        }
        String key = key(tenantId, provider.projectCode(), externalUserId);
        if (key == null) return;
        Instant now = clock.instant();
        if (tokens.size() >= MAX_TOKENS) {
            tokens.values().removeIf(held -> !held.expiresAt().isAfter(now));
            if (tokens.size() >= MAX_TOKENS) tokens.clear();
        }
        tokens.put(key, new Held(token.trim(), now.plus(MAX_HOLD)));
    }

    /** What Runtime should do for this employee in this system right now. */
    public Resolution resolve(String tenantId, String projectCode, String externalUserId) {
        String key = key(tenantId, projectCode, externalUserId);
        // The platform's own evaluation account has no business login; it only ever reads.
        if (key == null || McpEmployeeProtocolService.PLATFORM_EVAL_USER.equals(externalUserId.trim())) {
            return Resolution.OFF;
        }
        String mode = BusinessSsoProvider.EMPLOYEE_TOKEN_OFF;
        Set<String> origins = new LinkedHashSet<>();
        for (BusinessSsoProvider provider : store.list()) {
            if (!provider.active() || !provider.usesEmployeeToken()
                    || !projectCode.trim().equals(provider.projectCode())) {
                continue;
            }
            if (!BusinessSsoProvider.EMPLOYEE_TOKEN_REQUIRED.equals(mode)) mode = provider.employeeToken();
            // The token only ever goes back to the addresses this entry itself talks to.
            addOrigin(origins, provider.tokenUrl());
            addOrigin(origins, provider.userInfoUrl());
        }
        if (BusinessSsoProvider.EMPLOYEE_TOKEN_OFF.equals(mode) || origins.isEmpty()) {
            tokens.remove(key);
            return Resolution.OFF;
        }
        Held held = tokens.get(key);
        if (held != null && !held.expiresAt().isAfter(clock.instant())) {
            tokens.remove(key);
            held = null;
        }
        return new Resolution(mode, List.copyOf(origins), held == null ? null : held.token());
    }

    /** The business system refused the token: forget it so the employee is asked to sign in again. */
    public void expired(String tenantId, String projectCode, String externalUserId) {
        String key = key(tenantId, projectCode, externalUserId);
        if (key != null) tokens.remove(key);
    }

    private static String key(String tenantId, String projectCode, String externalUserId) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(externalUserId)) return null;
        return (tenantId == null ? "" : tenantId.trim()) + "\n" + projectCode.trim() + "\n" + externalUserId.trim();
    }

    private static void addOrigin(Set<String> origins, String url) {
        if (!StringUtils.hasText(url)) return;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(uri.getHost()) || !(scheme.equals("http") || scheme.equals("https"))) return;
            int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
            origins.add(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port);
        } catch (IllegalArgumentException ignored) {
            // an address that cannot be parsed is never a place to send a token
        }
    }

    private record Held(String token, Instant expiresAt) {
    }

    /**
     * @param mode    OFF, PREFERRED or REQUIRED
     * @param origins {@code scheme://host:port} the token may be sent to
     * @param token   the employee's token, or null when there is none to use
     */
    public record Resolution(String mode, List<String> origins, String token) {
        static final Resolution OFF = new Resolution(BusinessSsoProvider.EMPLOYEE_TOKEN_OFF, List.of(), null);

        @Override
        public String toString() {
            return "Resolution[mode=" + mode + ", origins=" + origins + ", token=" + (token == null ? "none" : "***") + "]";
        }
    }
}
