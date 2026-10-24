package com.enterprise.ai.control.identity;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.enterprise.ai.control.identity.PlatformPermissions.*;

/**
 * Entry permission for every platform-session console route.
 *
 * <p>The authentication interceptor applies this table after it has attested
 * the session, so a console route can never be reachable with a bare login.
 * The table is deliberately coarse: it names the permission a caller needs to
 * enter a domain at all. Controllers keep their finer checks (publish, debug,
 * project scope). Rules are matched in order, most specific first; a
 * protected route without a rule is denied.</p>
 */
public final class PlatformConsolePermissionPolicy {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> AUTHENTICATED = List.of();
    private static final List<String> PROJECT_CATALOG_READERS = List.of(
            PLATFORM_READ, AGENT_READ, WORKFLOW_READ, RUNOPS_READ, AUTOMATION_READ, KNOWLEDGE_READ,
            MODEL_READ, TOOL_ACL_READ, EMBED_READ, SKILL_READ, MCP_HUB_READ);

    /**
     * @param read  any of these permissions admits GET/HEAD requests
     * @param write any of these permissions admits every other method
     */
    public record Rule(String pattern, List<String> read, List<String> write, boolean projectInPath) {
        public List<String> requiredAnyOf(String method) {
            return safe(method) ? read : write;
        }

        /** The project code carried by {@code /api/registry/projects/{projectCode}/...} routes. */
        public String projectCode(String path) {
            if (!projectInPath || path == null) {
                return null;
            }
            String[] segments = path.split("/");
            return segments.length > 4 ? segments[4] : null;
        }
    }

    public static final List<Rule> RULES = List.of(
            // Session itself and the caller's own data.
            rule("/api/platform/auth/**", AUTHENTICATED, AUTHENTICATED),
            rule("/api/me/**", AUTHENTICATED, AUTHENTICATED),
            rule("/api/internal-services/health", AUTHENTICATED, AUTHENTICATED),
            // Public compatibility execution: anonymous callers are admitted, so a login adds identity only.
            rule("/api/runtime/agents/execute/**", AUTHENTICATED, AUTHENTICATED),

            // Platform administration.
            rule("/api/platform/account-management/**", List.of(PLATFORM_ADMIN), List.of(PLATFORM_ADMIN)),
            rule("/api/platform/auth-providers/**", List.of(PLATFORM_ADMIN), List.of(PLATFORM_ADMIN)),
            rule("/api/platform/users/**", List.of(PLATFORM_ADMIN), List.of(PLATFORM_ADMIN)),
            rule("/api/platform/roles/**", List.of(PLATFORM_ADMIN), List.of(PLATFORM_ADMIN)),
            rule("/api/compliance/**", List.of(MEMORY_ERASURE_MANAGE), List.of(MEMORY_ERASURE_MANAGE)),
            rule("/api/runtime-user-mappings/**",
                    List.of(RUNTIME_USER_MAPPING_MANAGE), List.of(RUNTIME_USER_MAPPING_MANAGE)),
            rule("/api/runtime/session-retention/**",
                    List.of(SESSION_RETENTION_MANAGE), List.of(SESSION_RETENTION_MANAGE)),

            // End users of the embedded chat and what they may invoke.
            rule("/api/platform/business-users/**", List.of(BUSINESS_USER_READ), List.of(BUSINESS_USER_MANAGE)),
            rule("/api/tool-acl/**", List.of(TOOL_ACL_READ), List.of(TOOL_ACL_MANAGE)),
            rule("/api/platform/embed/**", List.of(EMBED_READ), List.of(EMBED_MANAGE)),

            // Projects and business capabilities (developers, project scoped in controllers).
            rule("/api/platform/registry-enrollments/**", List.of(PLATFORM_WRITE), List.of(PLATFORM_WRITE)),
            projectRule("/api/registry/projects/*/instances/**", List.of(PLATFORM_READ), List.of(PLATFORM_WRITE)),
            projectRule("/api/registry/projects/*/page-workbench/**",
                    List.of(PLATFORM_READ), List.of(PLATFORM_WRITE)),
            // The bare project list feeds every project picker; callers without platform:read
            // get names only (CapabilityCompatibilityProxyController trims the rows).
            rule("/api/scan-projects", PROJECT_CATALOG_READERS, List.of(PLATFORM_WRITE)),
            rule("/api/scan-projects/**", List.of(PLATFORM_READ), List.of(PLATFORM_WRITE)),
            rule("/api/ai-assist/projects/**", List.of(PLATFORM_READ), List.of(PLATFORM_WRITE)),
            rule("/api/ai-coding-console/**",
                    List.of(PLATFORM_READ, WORKFLOW_READ), List.of(PLATFORM_WRITE, WORKFLOW_WRITE)),
            // Capability controllers check write/invoke and project scope themselves.
            rule("/api/capabilities/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/capability-review/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/api-market/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/tools/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/business-methods/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/business-capabilities/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/business-method-invocations/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/apis/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/api-invocations/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),
            rule("/api/api-graph/**", List.of(PLATFORM_READ), List.of(PLATFORM_READ)),

            // Knowledge.
            rule("/api/knowledge/**", List.of(KNOWLEDGE_READ), List.of(KNOWLEDGE_WRITE)),
            rule("/ai/retrieval/**", List.of(KNOWLEDGE_READ), List.of(KNOWLEDGE_READ)),
            rule("/ai/knowledge/**", List.of(KNOWLEDGE_READ), List.of(KNOWLEDGE_WRITE)),
            rule("/ai/file/**", List.of(KNOWLEDGE_READ), List.of(KNOWLEDGE_WRITE)),

            // Models.
            rule("/model/instances/**", List.of(MODEL_READ), List.of(MODEL_MANAGE)),
            rule("/model/templates/**", List.of(MODEL_READ), List.of(MODEL_MANAGE)),
            rule("/model/catalog/**", List.of(MODEL_READ), List.of(MODEL_MANAGE)),
            rule("/model/chat/**", List.of(MODEL_READ), List.of(MODEL_READ)),

            // Workflow, Agent and their run-time operations; controllers enforce the rest.
            rule("/api/workflows/**", List.of(WORKFLOW_READ), List.of(WORKFLOW_READ)),
            rule("/api/agents/**", List.of(AGENT_READ), List.of(AGENT_READ)),
            rule("/api/market/**", List.of(AGENT_READ), List.of(AGENT_PUBLISH)),
            rule("/api/runtime/evals/**", List.of(AGENT_EVALUATE), List.of(AGENT_EVALUATE)),
            rule("/api/runtime/agents/route-evaluation", List.of(AGENT_EVALUATE), List.of(AGENT_EVALUATE)),
            rule("/api/runtime/agents/sessions/**", List.of(AGENT_DEBUG), List.of(AGENT_DEBUG)),
            rule("/api/runtime/debug-sessions/**",
                    List.of(AGENT_DEBUG, WORKFLOW_DEBUG), List.of(AGENT_DEBUG, WORKFLOW_DEBUG)),
            rule("/api/runtime/interactions/**",
                    List.of(AGENT_DEBUG, WORKFLOW_DEBUG), List.of(AGENT_DEBUG, WORKFLOW_DEBUG)),
            rule("/api/runtime/tools/**", List.of(AGENT_READ, WORKFLOW_READ), List.of(AGENT_READ, WORKFLOW_READ)),
            rule("/api/runtime/compositions/**",
                    List.of(AGENT_READ, WORKFLOW_READ), List.of(AGENT_READ, WORKFLOW_READ)),
            rule("/api/skills/**", List.of(SKILL_READ), List.of(SKILL_READ)),
            rule("/api/skill-market/**", List.of(SKILL_READ), List.of(SKILL_READ)),
            rule("/api/mcp/**", List.of(MCP_HUB_READ), List.of(MCP_HUB_READ)),
            rule("/api/a2a-hub/**", List.of(A2A_HUB_READ), List.of(A2A_HUB_READ)),
            rule("/api/automations/**", List.of(AUTOMATION_READ), List.of(AUTOMATION_READ)),
            rule("/api/traces/**", List.of(RUNOPS_READ), List.of(RUNOPS_READ)),
            rule("/api/runops/**", List.of(RUNOPS_READ), List.of(RUNOPS_READ)),
            rule("/api/trace-center/**", List.of(RUNOPS_READ), List.of(RUNOPS_READ)),

            // Remaining platform routes are administrative until classified.
            rule("/api/platform/**", List.of(PLATFORM_ADMIN), List.of(PLATFORM_ADMIN)));

    public static Optional<Rule> ruleFor(String path) {
        if (path == null) {
            return Optional.empty();
        }
        return RULES.stream().filter(rule -> PATH_MATCHER.match(rule.pattern(), path)).findFirst();
    }

    /**
     * True when the session satisfies {@code anyOf}; an empty list means any login. A
     * project in the path must be inside the scope of the matching grant.
     */
    public static boolean admits(Rule rule, String method, String path, PlatformAuthenticatedSession session) {
        List<String> anyOf = rule.requiredAnyOf(method);
        if (anyOf.isEmpty()) {
            return true;
        }
        if (session == null || session.permissions() == null) {
            return false;
        }
        String projectCode = rule.projectCode(path);
        if (projectCode != null) {
            return anyOf.stream().anyMatch(permission ->
                    session.hasResourcePermission(permission, "PROJECT", null, projectCode));
        }
        return session.permissions().contains(ALL) || anyOf.stream().anyMatch(session.permissions()::contains);
    }

    private static Rule rule(String pattern, List<String> read, List<String> write) {
        return new Rule(pattern, read, write, false);
    }

    private static Rule projectRule(String pattern, List<String> read, List<String> write) {
        return new Rule(pattern, read, write, true);
    }

    private static boolean safe(String method) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return HttpMethod.GET.matches(normalized) || HttpMethod.HEAD.matches(normalized);
    }

    private PlatformConsolePermissionPolicy() {
    }
}
