package com.enterprise.ai.agent.platform.control.context;

import org.springframework.util.StringUtils;

/**
 * Shared projectCode / projectId compatibility for context governance paths.
 * No Spring, no database, no tenant or visibility semantics.
 */
public final class ContextProjectIdentity {

    private ContextProjectIdentity() {
    }

    public static boolean hasProjectIdentity(String projectCode, Long projectId) {
        return StringUtils.hasText(projectCode) || projectId != null;
    }

    /**
     * Phase-5.2 compatible project identity match.
     */
    public static boolean matches(String leftProjectCode,
                                  Long leftProjectId,
                                  String rightProjectCode,
                                  Long rightProjectId) {
        boolean leftHasCode = StringUtils.hasText(leftProjectCode);
        boolean leftHasId = leftProjectId != null;
        boolean rightHasCode = StringUtils.hasText(rightProjectCode);
        boolean rightHasId = rightProjectId != null;

        if (leftHasCode && rightHasCode && !leftProjectCode.equals(rightProjectCode)) {
            return false;
        }
        if (leftHasId && rightHasId && !leftProjectId.equals(rightProjectId)) {
            return false;
        }
        if (!leftHasCode && !leftHasId && !rightHasCode && !rightHasId) {
            return true;
        }
        boolean leftHasAny = leftHasCode || leftHasId;
        boolean rightHasAny = rightHasCode || rightHasId;
        if (leftHasAny != rightHasAny) {
            return false;
        }
        boolean codeComparable = leftHasCode && rightHasCode;
        boolean idComparable = leftHasId && rightHasId;
        if (!codeComparable && !idComparable) {
            return false;
        }
        return true;
    }

    public static void requireMatch(String leftProjectCode,
                                    Long leftProjectId,
                                    String rightProjectCode,
                                    Long rightProjectId,
                                    String message) {
        if (!matches(leftProjectCode, leftProjectId, rightProjectCode, rightProjectId)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Stable namespace key token for project scope. projectCode wins to preserve legacy keys.
     */
    public static String namespaceKeyToken(String projectCode, Long projectId) {
        if (StringUtils.hasText(projectCode)) {
            return projectCode.trim().toLowerCase();
        }
        if (projectId != null) {
            return "pid-" + projectId;
        }
        return null;
    }
}
