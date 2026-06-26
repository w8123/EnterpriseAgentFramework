package com.enterprise.ai.agent.runtime.host;


import com.enterprise.ai.agent.runtime.*;
import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmbeddedRuntimeDispatchService {

    static final String EMBEDDED_EXECUTE_PATH = "/eaf/runtime/embedded/execute";

    private final AiRegistryService registryService;

    public EmbeddedRuntimeDispatchResult dispatch(EmbeddedRuntimeDispatchRequest request) {
        validateRequest(request);
        ProjectInstanceEntity instance = registryService.findInstance(request.projectCode(), request.instanceId());
        RuntimeGovernancePolicy policy = registryService.governancePolicy(instance);
        validateInstance(instance, policy);

        String baseUrl = trimTrailingSlash(instance.getBaseUrl());
        String dispatchUrl = baseUrl + EMBEDDED_EXECUTE_PATH;
        try {
            EmbeddedRuntimeDispatchResult remote = RestClient.builder()
                    .baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri(EMBEDDED_EXECUTE_PATH)
                    .body(toRemoteRequest(request))
                    .retrieve()
                    .body(EmbeddedRuntimeDispatchResult.class);
            if (remote == null) {
                return failure(request, "EMPTY_RESPONSE", "业务系统 Embedded Runtime 返回为空");
            }
            return new EmbeddedRuntimeDispatchResult(
                    remote.success(),
                    remote.answer(),
                    request.projectCode(),
                    request.instanceId(),
                    dispatchUrl,
                    remote.steps(),
                    remote.metadata(),
                    remote.errorCode(),
                    remote.errorMessage()
            );
        } catch (RestClientResponseException ex) {
            return failure(request, "REMOTE_HTTP_" + ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            return failure(request, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private Map<String, Object> toRemoteRequest(EmbeddedRuntimeDispatchRequest request) {
        return Map.of(
                "agentKey", request.agentKey(),
                "message", request.message() == null ? "" : request.message(),
                "sessionId", request.sessionId() == null ? "" : request.sessionId(),
                "userId", request.userId() == null ? "" : request.userId(),
                "context", request.context() == null ? Map.of() : request.context(),
                "graphSpec", request.graphSpec() == null ? Map.of() : request.graphSpec()
        );
    }

    private void validateRequest(EmbeddedRuntimeDispatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("dispatch request 不能为空");
        }
        if (!StringUtils.hasText(request.projectCode())) {
            throw new IllegalArgumentException("projectCode 不能为空");
        }
        if (!StringUtils.hasText(request.instanceId())) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        if (!StringUtils.hasText(request.agentKey())) {
            throw new IllegalArgumentException("agentKey 不能为空");
        }
    }

    private void validateInstance(ProjectInstanceEntity instance, RuntimeGovernancePolicy policy) {
        if (!"ONLINE".equalsIgnoreCase(instance.getStatus())) {
            throw new IllegalStateException("Runtime 实例不在线: " + instance.getStatus());
        }
        if (!StringUtils.hasText(instance.getBaseUrl())) {
            throw new IllegalStateException("Runtime 实例缺少 baseUrl");
        }
        if (RuntimeInstanceRoles.isCapabilityHost(instance)) {
            throw new IllegalStateException("Capability Host 只能提供业务能力调用，不能作为 Agent Runtime 执行目标");
        }
        if (policy != null && policy.disabled()) {
            throw new IllegalStateException("Runtime 实例已被治理策略禁用: " + policy.message());
        }
        if (policy != null && Boolean.FALSE.equals(policy.allowEmbeddedExecution())) {
            throw new IllegalStateException("Runtime 实例未被允许执行 Embedded Runtime: " + policy.message());
        }
    }

    private EmbeddedRuntimeDispatchResult failure(EmbeddedRuntimeDispatchRequest request,
                                                  String errorCode,
                                                  String errorMessage) {
        return new EmbeddedRuntimeDispatchResult(
                false,
                errorMessage,
                request.projectCode(),
                request.instanceId(),
                null,
                List.of(),
                Map.of(),
                errorCode,
                errorMessage
        );
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
