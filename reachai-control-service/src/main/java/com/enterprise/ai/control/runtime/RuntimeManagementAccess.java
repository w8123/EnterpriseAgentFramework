package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

/**
 * Project-scope authorization shared by Agent, Workflow and RunOps console
 * routes. Runtime remains the resource owner; Control resolves the canonical
 * project identity and enforces the platform grant before returning data or
 * forwarding a mutation.
 */
@Component
@RequiredArgsConstructor
final class RuntimeManagementAccess {

    private final PlatformRequestAuthorization requestAuthorization;
    private final CapabilityProjectOnboardingClient capabilityProjectClient;

    PlatformAuthenticatedSession require(String permission) {
        return requestAuthorization.requirePermission(currentRequest(), permission);
    }

    PlatformAuthenticatedSession requireProject(
            String permission,
            Long projectId,
            String projectCode) {
        PlatformAuthenticatedSession session = require(permission);
        requireProject(session, permission, projectId, projectCode);
        return session;
    }

    void requireProject(
            PlatformAuthenticatedSession session,
            String permission,
            Long projectId,
            String projectCode) {
        String canonicalProjectCode = canonicalProjectCode(projectId, projectCode);
        if (session == null || !session.hasResourcePermission(
                permission, "PROJECT", null, canonicalProjectCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "platform permission is required for the Runtime project: " + permission);
        }
    }

    void requireBodyProject(
            PlatformAuthenticatedSession session,
            String permission,
            Map<String, Object> body) {
        requireProject(
                session,
                permission,
                projectId(body),
                projectCode(body));
    }

    void requireResponseProject(
            PlatformAuthenticatedSession session,
            String permission,
            ResponseEntity<?> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            return;
        }
        Object payload = response.getBody();
        requireProject(
                session,
                permission,
                projectId(payload),
                projectCode(payload));
    }

    String projectCode(Object payload) {
        Map<?, ?> source = projectSource(payload);
        return source == null ? null : text(source.get("projectCode"));
    }

    private Long projectId(Object payload) {
        Map<?, ?> source = projectSource(payload);
        return source == null ? null : longValue(source.get("projectId"));
    }

    private Map<?, ?> projectSource(Object payload) {
        if (!(payload instanceof Map<?, ?> root)) return null;
        if (root.containsKey("projectCode") || root.containsKey("projectId")) return root;
        for (String key : new String[]{
                "summary", "agent", "workflow", "dataset", "experiment",
                "workingCopy", "workingCopyDefinition"}) {
            Object candidate = root.get(key);
            if (candidate instanceof Map<?, ?> map
                    && (map.containsKey("projectCode") || map.containsKey("projectId"))) {
                return map;
            }
        }
        return root;
    }

    private String canonicalProjectCode(Long projectId, String projectCode) {
        String requestedCode = text(projectCode);
        if (projectId == null) return requestedCode;

        Map<String, Object> project = capabilityProjectClient.getProjectById(projectId);
        if (project == null || project.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Runtime project scope could not be resolved");
        }
        Long actualId = longValue(project.get("id"));
        if (actualId == null) actualId = longValue(project.get("projectId"));
        String actualCode = text(project.get("projectCode"));
        if (actualId != null && !Objects.equals(actualId, projectId)) {
            throw new IllegalArgumentException("project lookup returned a different projectId");
        }
        if (!StringUtils.hasText(actualCode)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Runtime project lookup returned no projectCode");
        }
        if (StringUtils.hasText(requestedCode) && !requestedCode.equalsIgnoreCase(actualCode)) {
            throw new IllegalArgumentException(
                    "projectId and projectCode do not refer to the same project");
        }
        return actualCode;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform request is required");
    }

    private String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            String normalized = text(value);
            return normalized == null ? null : Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("projectId must be numeric");
        }
    }
}
