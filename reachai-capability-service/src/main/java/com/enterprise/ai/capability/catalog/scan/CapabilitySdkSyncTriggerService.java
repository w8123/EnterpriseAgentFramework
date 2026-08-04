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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

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
        requestBody.put("schema", "reachai.registry-capability-scan-request.v1");
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
        } catch (RestClientResponseException ex) {
            throw downstreamHttpFailure(targetUrl, ex);
        } catch (ResourceAccessException ex) {
            throw syncRequestFailure(
                    "SDK_SYNC_TARGET_UNREACHABLE",
                    targetUrl,
                    "ReachAI 无法连接业务系统同步接口 " + targetUrl
                            + "。请检查 reachai.project.base-url 是否为 ReachAI 服务端可达地址；"
                            + "分机、容器或集群部署不要使用 localhost，并检查网关路由、防火墙和连接超时。",
                    ex);
        } catch (RestClientException ex) {
            throw syncRequestFailure(
                    "SDK_SYNC_REQUEST_FAILED",
                    targetUrl,
                    "ReachAI 调用业务系统同步接口失败 " + targetUrl + "：" + ex.getMessage(),
                    ex);
        }
    }

    private SdkSyncRequestException downstreamHttpFailure(String targetUrl,
                                                          RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == 401 || status == 403) {
            return syncRequestFailure(
                    "SDK_SYNC_AUTH_REJECTED",
                    targetUrl,
                    "业务系统返回 HTTP " + status + "。请确认业务登录/JWT和 CSRF 已允许 POST "
                            + targetUrl
                            + " 到达 ReachAI Starter Controller；同时保留 Starter 签名校验，并确认项目 appKey/appSecret 一致。",
                    ex);
        }
        if (status == 404 || status == 405) {
            return syncRequestFailure(
                    "SDK_SYNC_ROUTE_NOT_FOUND",
                    targetUrl,
                    "业务系统返回 HTTP " + status + "。请检查 reachai.project.base-url、context-path，"
                            + "以及网关是否已将 /reachai/registry/** 转发到 Starter 所在业务服务并允许 POST。",
                    ex);
        }
        return syncRequestFailure(
                "SDK_SYNC_DOWNSTREAM_ERROR",
                targetUrl,
                "业务系统同步接口 " + targetUrl + " 返回 HTTP " + status
                        + "。请查看业务系统和网关日志中的同一请求时间点。",
                ex);
    }

    private SdkSyncRequestException syncRequestFailure(String code,
                                                       String targetUrl,
                                                       String message,
                                                       Throwable cause) {
        return new SdkSyncRequestException(code, targetUrl, message, cause);
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

    public static class SdkSyncRequestException extends IllegalStateException {

        private final String code;
        private final String targetUrl;

        public SdkSyncRequestException(String code, String targetUrl, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.targetUrl = targetUrl;
        }

        public String code() {
            return code;
        }

        public String targetUrl() {
            return targetUrl;
        }
    }
}
