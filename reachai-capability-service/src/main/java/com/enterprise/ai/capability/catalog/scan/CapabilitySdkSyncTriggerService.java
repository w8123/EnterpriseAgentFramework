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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class CapabilitySdkSyncTriggerService {
    private static final ObjectMapper RESPONSE_JSON = new ObjectMapper();

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
            // SDK intake can commit a large inventory after its original 60 second lease expires.
            // This callback lease encloses the documented 120s SDK lease; Control retains 135s.
            RestTemplate restTemplate = restTemplateBuilder.connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(125)).build();
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(targetUrl, HttpMethod.POST, request, Map.class);
            Map<String, Object> body = response.getBody() == null
                    ? Map.of()
                    : new LinkedHashMap<>((Map<String, Object>) response.getBody());
            Map<String, Object> verified = verifiedResponse(body, project, instance, targetUrl);
            return new SdkSyncTriggerResponse(
                    project.getId(),
                    project.getProjectCode(),
                    instance.getInstanceId(),
                    targetUrl,
                    intValue(verified.get("capabilityCount")),
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

    /** HTTP 200 alone cannot prove that the business callback reached the Starter and Registry. */
    private Map<String, Object> verifiedResponse(Map<String, Object> body, ScanProjectEntity project,
                                                  ProjectInstanceEntity instance, String targetUrl) {
        JsonNode root = RESPONSE_JSON.valueToTree(body);
        if (explicitFailure(root)) {
            throw syncRequestFailure("SDK_SYNC_BUSINESS_FAILED", targetUrl,
                    "业务系统返回同步失败；尚不能确认本次同步回执。请核对业务服务日志与来源记录，超时不代表平台已回滚。", null);
        }
        JsonNode nativeResponse = root.path("success").asBoolean(false) && root.path("data").isObject()
                ? root.path("data") : root;
        if (!nativeResponse.isObject() || explicitFailure(nativeResponse)
                || !instance.getInstanceId().equals(nativeResponse.path("instanceId").asText())
                || !nonNegativeCount(nativeResponse.get("capabilityCount"))
                || nativeResponse.has("httpApiCount") && !nonNegativeCount(nativeResponse.get("httpApiCount"))
                || !nativeResponse.path("registryResponse").isTextual()) throw invalidResponse(targetUrl);
        JsonNode receipt;
        try {
            receipt = RESPONSE_JSON.readTree(nativeResponse.path("registryResponse").asText());
        } catch (Exception invalidJson) {
            // Do not retain the parser exception: its source excerpt could contain business data.
            throw invalidResponse(targetUrl);
        }
        int methods = nativeResponse.path("capabilityCount").intValue();
        int httpApis = nativeResponse.path("httpApiCount").asInt(0);
        if (receipt == null || !receipt.isObject() || explicitFailure(receipt)
                || !StringUtils.hasText(receipt.path("syncId").asText())
                || !receipt.path("projectId").isIntegralNumber()
                || receipt.path("projectId").longValue() != project.getId()
                || !project.getProjectCode().equals(receipt.path("projectCode").asText())
                || !nonNegativeCount(receipt.get("received")) || receipt.path("received").intValue() != methods) {
            throw invalidResponse(targetUrl);
        }
        if (httpApis > 0 && (!receipt.path("httpApis").path("supported").asBoolean(false)
                || !nonNegativeCount(receipt.path("httpApis").get("received"))
                || !nonNegativeCount(receipt.path("httpApis").get("observed"))
                || receipt.path("httpApis").path("received").intValue() != httpApis
                || receipt.path("httpApis").path("observed").intValue() != httpApis)) throw invalidResponse(targetUrl);
        @SuppressWarnings("unchecked")
        Map<String, Object> verified = RESPONSE_JSON.convertValue(nativeResponse, Map.class);
        return verified;
    }

    private boolean nonNegativeCount(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0;
    }

    private boolean explicitFailure(JsonNode value) {
        if (value.path("success").isBoolean() && !value.path("success").booleanValue()) return true;
        if (value.hasNonNull("errorCode") && !"0".equals(value.path("errorCode").asText())) return true;
        if (!value.hasNonNull("code")) return false;
        String code = value.path("code").asText();
        return !"0".equals(code) && !"200".equals(code);
    }

    private SdkSyncRequestException invalidResponse(String targetUrl) {
        return syncRequestFailure("SDK_SYNC_RESPONSE_INVALID", targetUrl,
                "业务系统未返回可验证的 Starter 同步回执，或项目、实例、接口数量不一致；请检查业务服务日志与来源记录。", null);
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
