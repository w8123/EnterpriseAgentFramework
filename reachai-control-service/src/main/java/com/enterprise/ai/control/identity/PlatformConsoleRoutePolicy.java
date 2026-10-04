package com.enterprise.ai.control.identity;

import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for Control routes that are management-console
 * operations. Protocol routes with their own credentials are deliberately
 * absent: Embed, SDK registry, AI Coding key/task, and public agent gateway.
 */
public final class PlatformConsoleRoutePolicy {

    public enum CredentialDomain {
        PLATFORM_SESSION,
        PUBLIC_LOGIN,
        INDEPENDENT_PROTOCOL,
        COMPATIBILITY_PENDING,
        UNCLASSIFIED
    }

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** Public compatibility execution optionally accepts a validated platform login. */
    public static final List<String> OPTIONAL_SESSION_PATH_PATTERNS = List.of(
            "/api/runtime/agents/execute/**");

    public static final List<String> PROTECTED_PATH_PATTERNS = List.of(
            "/model/instances",
            "/model/instances/**",
            "/api/platform/**",
            "/api/knowledge/**",
            "/api/ai-coding-console/**",
            "/api/ai-assist/projects/*/**",
            "/api/registry/projects/*/page-workbench/**",
            "/api/internal-services/health",
            "/api/context/**",
            "/api/tool-acl/**",
            "/api/a2a-hub/**",
            "/api/mcp/**",
            "/api/market/**",
            "/api/workflows/**",
            "/api/agents/**",
            "/api/skills/**",
            "/api/skill-market/**",
            "/api/traces/**",
            "/api/runops/**",
            "/api/automations",
            "/api/automations/**",
            "/api/trace-center/**",
            "/api/runtime/evals/**",
            "/api/runtime/agents/sessions/**",
            "/api/runtime/session-retention/**",
            "/api/runtime/agents/route-evaluation",
            "/api/runtime/tools/**",
            "/api/runtime/compositions/**",
            "/api/runtime/interactions/**",
            "/api/runtime/debug-sessions/**",
            "/api/capabilities/**",
            "/api/capability-review/**",
            "/api/api-market/**",
            "/api/tools/**",
            "/api/business-methods/**",
            "/api/business-method-invocations/**",
            "/api/apis/**",
            "/api/api-invocations/**",
            "/api/api-graph/**",
            "/api/tool-retrieval/**",
            "/api/scan-projects/**",
            "/api/scan-modules/**",
            "/api/semantic-docs/**",
            "/api/domains/**");

    public static final List<String> EXCLUDED_PATH_PATTERNS = List.of(
            "/api/platform/auth/login");

    /**
     * The subset of independent protocol endpoints that is authenticated by a
     * project-scoped AI Coding key. Keep it separate from Embed/SDK routes so
     * the key interceptor never accidentally becomes a catch-all protocol gate.
     */
    public static final List<String> AI_CODING_KEY_PATH_PATTERNS = List.of(
            "/api/ai-coding/projects/**",
            "/api/ai-coding/tasks/**",
            "/api/ai-coding/handoffs/**",
            "/api/workflows/ai-coding/**",
            "/api/workflows/*/ai-coding/**");

    /**
     * Public and non-console endpoints authenticate through their own protocol.
     * Keep this list explicit so a newly mapped controller endpoint cannot be
     * mistaken for either an anonymous console API or a platform-session API.
     */
    public static final List<String> INDEPENDENT_PROTOCOL_PATH_PATTERNS = independentProtocolPatterns();

    private static List<String> independentProtocolPatterns() {
        List<String> patterns = new ArrayList<>(List.of(
            "/api/embed/**",
            "/embed/**",
            "/api/v1/agents/**",
            "/api/ai-assist/skills/**",
            "/api/ai-assist/artifacts/**",
            "/api/knowledge-ingress/**",
            "/.well-known/agent-card.json",
            "/mcp/**",
            "/a2a/**",
            "/gateway/**",
            "/internal/**"));
        patterns.addAll(OPTIONAL_SESSION_PATH_PATTERNS);
        patterns.addAll(AI_CODING_KEY_PATH_PATTERNS);
        return List.copyOf(patterns);
    }

    /**
     * Legacy SDK/console compatibility paths are deliberately classified but
     * cannot be treated as closed until their platform-session-or-SDK-signature
     * adapter is implemented.
     */
    public static final List<String> COMPATIBILITY_PENDING_PATH_PATTERNS = List.of(
            "/api/registry/**");

    /**
     * Returns the unique credential domain selected for a mapped Control
     * endpoint. Platform-session mappings intentionally take precedence over
     * broad compatibility paths such as /api/registry/**.
     */
    public static CredentialDomain credentialDomainFor(String path) {
        if (matches(EXCLUDED_PATH_PATTERNS, path)) {
            return CredentialDomain.PUBLIC_LOGIN;
        }
        // Workflow AI Coding lives below the broader /api/workflows/** console
        // namespace, but it has its own project-key protocol. Check this
        // narrower independent contract before the broad console mapping.
        if (matches(INDEPENDENT_PROTOCOL_PATH_PATTERNS, path)) {
            return CredentialDomain.INDEPENDENT_PROTOCOL;
        }
        if (matches(PROTECTED_PATH_PATTERNS, path)) {
            return CredentialDomain.PLATFORM_SESSION;
        }
        if (matches(COMPATIBILITY_PENDING_PATH_PATTERNS, path)) {
            return CredentialDomain.COMPATIBILITY_PENDING;
        }
        return CredentialDomain.UNCLASSIFIED;
    }

    private static boolean matches(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private PlatformConsoleRoutePolicy() {
    }
}
