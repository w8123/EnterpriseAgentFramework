package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ControlAiCodingAccessGuard {

    public static final String AI_CODING_HEADER = "X-ReachAI-AiCoding-Key";

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final RuntimeProxyClient runtimeClient;

    public void requireProjectAccess(Long projectId, String aiCodingKey) {
        requireKey(aiCodingKey);
        Map<String, Object> project = requireProject(projectId);
        Map<String, Object> access = mapValue(project.get("aiCodingAccess"));
        String expected = stringValue(access.get("accessKey"));
        if (!booleanValue(access.get("enabled")) || !StringUtils.hasText(expected)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI Coding access is disabled for this project");
        }
        if (!expected.trim().equals(aiCodingKey.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI Coding key is invalid");
        }
    }

    public void requireWorkflowAccess(String workflowId, String aiCodingKey) {
        requireKey(aiCodingKey);
        Long projectId = workflowProjectId(workflowId);
        requireProjectAccess(projectId, aiCodingKey);
    }

    public void requireWorkflowCreateAccess(Map<String, Object> request, String aiCodingKey) {
        requireKey(aiCodingKey);
        Long projectId = request == null ? null : longValue(request.get("projectId"));
        if (projectId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required for Workflow AI Coding create");
        }
        requireProjectAccess(projectId, aiCodingKey);
    }

    private void requireKey(String aiCodingKey) {
        if (!StringUtils.hasText(aiCodingKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-ReachAI-AiCoding-Key is required");
        }
    }

    private Map<String, Object> requireProject(Long projectId) {
        if (projectId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required");
        }
        try {
            return capabilityClient.getOnboardingProjectById(projectId);
        } catch (FeignException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found: " + projectId, ex);
        }
    }

    private Long workflowProjectId(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflowId is required");
        }
        try {
            ResponseEntity<Object> response = runtimeClient.workflowAiCodingContext(workflowId.trim());
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(
                        HttpStatus.valueOf(response.getStatusCode().value()),
                        "workflow context lookup failed");
            }
            Object body = response.getBody();
            Map<String, Object> root = mapValue(body);
            Long projectId = longValue(mapValue(root.get("workflow")).get("projectId"));
            if (projectId == null) {
                projectId = longValue(root.get("projectId"));
            }
            if (projectId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "workflow context does not include projectId");
            }
            return projectId;
        } catch (FeignException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow not found: " + workflowId, ex);
        } catch (FeignException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "workflow context lookup failed: " + ex.status(), ex);
        }
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) source;
        return cast;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId must be numeric", ex);
        }
    }
}
