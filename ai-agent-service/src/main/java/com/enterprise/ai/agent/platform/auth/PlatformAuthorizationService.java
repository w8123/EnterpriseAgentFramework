package com.enterprise.ai.agent.platform.auth;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

@Service
public class PlatformAuthorizationService {

    public boolean isAllowed(PlatformPrincipal principal, String method, String path) {
        return isAllowed(principal, method, path, null, null);
    }

    public boolean canAccessProject(PlatformPrincipal principal, Long projectId, String projectCode) {
        if (principal == null || principal.permissions() == null) {
            return false;
        }
        if (principal.permissions().contains("*")) {
            return true;
        }
        if (hasGlobalScope(principal) || !hasProjectScope(principal)) {
            return true;
        }
        String projectIdText = projectId == null ? null : String.valueOf(projectId);
        return matchesProjectScope(principal, projectCode, projectIdText);
    }

    public boolean isAllowed(PlatformPrincipal principal,
                             String method,
                             String path,
                             String projectCode,
                             String projectId) {
        if (principal == null || principal.permissions() == null) {
            return false;
        }
        Set<String> permissions = principal.permissions();
        if (permissions.contains("*")) {
            return true;
        }
        if (isPlatformAdminPath(path)) {
            return permissions.contains("platform:admin");
        }
        boolean permitted = isRead(method)
                ? permissions.contains("platform:read")
                || permissions.contains("platform:write")
                || permissions.contains("platform:admin")
                : permissions.contains("platform:write") || permissions.contains("platform:admin");
        if (!permitted) {
            return false;
        }
        if (hasGlobalScope(principal) || !hasProjectScope(principal)) {
            return true;
        }
        return matchesProjectScope(principal, projectCode, projectId);
    }

    private boolean isPlatformAdminPath(String path) {
        return StringUtils.hasText(path)
                && (path.startsWith("/api/platform/users")
                || path.startsWith("/api/platform/roles")
                || path.startsWith("/api/platform/auth-providers")
                || path.startsWith("/api/platform/business-users"));
    }

    private boolean isRead(String method) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return "GET".equals(normalized) || "HEAD".equals(normalized) || "OPTIONS".equals(normalized);
    }

    private boolean hasGlobalScope(PlatformPrincipal principal) {
        return principal.roleGrants().stream()
                .anyMatch(grant -> !StringUtils.hasText(grant.scopeType())
                        || "GLOBAL".equalsIgnoreCase(grant.scopeType())
                        || "*".equals(grant.scopeValue()));
    }

    private boolean hasProjectScope(PlatformPrincipal principal) {
        return principal.roleGrants().stream()
                .anyMatch(grant -> "PROJECT".equalsIgnoreCase(grant.scopeType()));
    }

    private boolean matchesProjectScope(PlatformPrincipal principal, String projectCode, String projectId) {
        if (!StringUtils.hasText(projectCode) && !StringUtils.hasText(projectId)) {
            return false;
        }
        return principal.roleGrants().stream()
                .filter(grant -> "PROJECT".equalsIgnoreCase(grant.scopeType()))
                .anyMatch(grant -> scopeMatches(grant.scopeValue(), projectCode, projectId));
    }

    private boolean scopeMatches(String scopeValue, String projectCode, String projectId) {
        if (!StringUtils.hasText(scopeValue)) {
            return false;
        }
        String normalized = scopeValue.trim();
        if (StringUtils.hasText(projectCode) && normalized.equalsIgnoreCase(projectCode.trim())) {
            return true;
        }
        return StringUtils.hasText(projectId) && normalized.equals(projectId.trim());
    }
}
