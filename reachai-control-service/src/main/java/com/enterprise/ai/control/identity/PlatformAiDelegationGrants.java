package com.enterprise.ai.control.identity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Intersects grants captured at connection creation with the user's live grants. */
final class PlatformAiDelegationGrants {
    private PlatformAiDelegationGrants() {
    }

    static List<PlatformPermissionGrant> intersect(List<PlatformPermissionGrant> original,
                                                    List<PlatformPermissionGrant> current) {
        Set<PlatformPermissionGrant> result = new LinkedHashSet<>();
        for (PlatformPermissionGrant left : original == null ? List.<PlatformPermissionGrant>of() : original) {
            for (PlatformPermissionGrant right : current == null ? List.<PlatformPermissionGrant>of() : current) {
                PlatformPermissionGrant intersection = intersect(left, right);
                if (intersection != null) result.add(intersection);
            }
        }
        return List.copyOf(result);
    }

    static List<String> roles(List<String> original, List<String> current,
                              List<PlatformPermissionGrant> grants) {
        if (original == null || current == null) return List.of();
        boolean globalAll = grants.stream().anyMatch(grant -> grant.grantsGlobal(PlatformPermissions.ALL));
        List<String> result = new ArrayList<>();
        for (String role : original) {
            if (role == null || current.stream().noneMatch(role::equals)) continue;
            // Some existing authorization paths recognize this role directly.
            // It must never outlive the full global grant on the delegation.
            if ("PLATFORM_ADMIN".equalsIgnoreCase(role) && !globalAll) continue;
            if (!result.contains(role)) result.add(role);
        }
        return List.copyOf(result);
    }

    private static PlatformPermissionGrant intersect(PlatformPermissionGrant left,
                                                     PlatformPermissionGrant right) {
        if (left == null || right == null) return null;
        String permission = narrowerPermission(left.permissionCode(), right.permissionCode());
        if (permission == null) return null;
        String leftType = normalizeType(left.scopeType());
        String rightType = normalizeType(right.scopeType());
        String leftValue = normalizeValue(left.scopeValue());
        String rightValue = normalizeValue(right.scopeValue());
        if (!validScope(leftType, leftValue) || !validScope(rightType, rightValue)) return null;
        if (isGlobal(leftType, leftValue)) return new PlatformPermissionGrant(permission, rightType, rightValue);
        if (isGlobal(rightType, rightValue)) return new PlatformPermissionGrant(permission, leftType, leftValue);
        if (leftType.equals(rightType) && leftValue.equals(rightValue)
                && ("PROJECT".equals(leftType) || "WORKSPACE".equals(leftType))) {
            return new PlatformPermissionGrant(permission, leftType, leftValue);
        }
        // Workspace/project relationships are not inferred from names. A
        // later role-scope change can require the user to reconnect.
        return null;
    }

    private static String narrowerPermission(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return null;
        if ("*".equals(left)) return right;
        if ("*".equals(right) || Objects.equals(left, right)) return left;
        return null;
    }

    private static boolean isGlobal(String type, String value) {
        return "GLOBAL".equals(type) && "*".equals(value);
    }

    private static boolean validScope(String type, String value) {
        if (type == null || value == null) return false;
        if ("GLOBAL".equals(type)) return "*".equals(value);
        return ("PROJECT".equals(type) || "WORKSPACE".equals(type)) && !"*".equals(value);
    }

    private static String normalizeType(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeValue(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
