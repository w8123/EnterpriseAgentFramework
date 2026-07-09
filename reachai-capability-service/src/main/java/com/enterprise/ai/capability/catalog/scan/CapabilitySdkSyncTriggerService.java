package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.reach.sdk.auth.ReachAiSignatureHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiSigner;
import com.enterprise.ai.reach.sdk.client.ReachAiClientConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CapabilitySdkSyncTriggerService {

    private final ScanProjectMapper scanProjectMapper;
    private final ProjectInstanceMapper instanceMapper;
    private final RegistrySecurityService registrySecurityService;
    private final RestTemplateBuilder restTemplateBuilder;

    public SdkSyncTriggerResponse triggerScan(Long projectId) {
        ScanProjectEntity project = scanProjectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Scan project does not exist: " + projectId);
        }
        ProjectInstanceEntity instance = latestOnlineInstance(project);
        if (instance == null) {
            throw new IllegalStateException("No online SDK instance found for project: " + project.getProjectCode());
        }
        RegistryCredentialEntity credential = registrySecurityService
                .findPrimaryActiveCredential(project.getProjectCode())
                .orElseThrow(() -> new IllegalStateException(
                        "No active SDK registry credential found for project: " + project.getProjectCode()));
        String targetUrl = sdkSyncUrl(project, instance);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("source", "API_MANUAL_SCAN");
        requestBody.put("projectId", project.getId());
        requestBody.put("projectCode", project.getProjectCode());
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody,
                signedHeaders(project, credential, targetUrl));
        try {
            RestTemplate restTemplate = restTemplateBuilder.build();
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(targetUrl, HttpMethod.POST, request, Map.class);
            Map<String, Object> body = response.getBody() == null
                    ? Map.of()
                    : new LinkedHashMap<>((Map<String, Object>) response.getBody());
            return new SdkSyncTriggerResponse(
                    project.getId(),
                    project.getProjectCode(),
                    instance.getInstanceId(),
                    targetUrl,
                    intValue(body.get("capabilityCount")),
                    body);
        } catch (RestClientException ex) {
            throw new IllegalStateException("SDK instance scan request failed: " + ex.getMessage(), ex);
        }
    }

    private ProjectInstanceEntity latestOnlineInstance(ScanProjectEntity project) {
        return instanceMapper.selectOne(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectId, project.getId())
                .eq(ProjectInstanceEntity::getProjectCode, project.getProjectCode())
                .eq(ProjectInstanceEntity::getStatus, "ONLINE")
                .orderByDesc(ProjectInstanceEntity::getLastHeartbeatAt)
                .orderByDesc(ProjectInstanceEntity::getId)
                .last("limit 1"));
    }

    private HttpHeaders signedHeaders(ScanProjectEntity project,
                                      RegistryCredentialEntity credential,
                                      String targetUrl) {
        ReachAiSignatureHeaders signatureHeaders = ReachAiSigner.sign(ReachAiClientConfig.builder()
                .endpoint(targetUrl)
                .projectCode(project.getProjectCode())
                .projectName(project.getName())
                .appKey(credential.getAppKey())
                .appSecret(credential.getAppSecret())
                .build());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        signatureHeaders.toHttpHeaders().forEach(headers::set);
        return headers;
    }

    private String sdkSyncUrl(ScanProjectEntity project, ProjectInstanceEntity instance) {
        String baseUrl = firstText(instance.getBaseUrl(), project.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("SDK instance baseUrl is empty for project: " + project.getProjectCode());
        }
        return trimTrailingSlash(baseUrl)
                + normalizeContextPath(project.getContextPath())
                + "/reachai/registry/capabilities/sync";
    }

    private String normalizeContextPath(String contextPath) {
        if (!StringUtils.hasText(contextPath) || "/".equals(contextPath.trim())) {
            return "";
        }
        String normalized = contextPath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return trimTrailingSlash(normalized);
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public record SdkSyncTriggerResponse(
            Long projectId,
            String projectCode,
            String instanceId,
            String targetUrl,
            int capabilityCount,
            Map<String, Object> businessResponse
    ) {
    }
}
