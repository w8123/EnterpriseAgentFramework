package com.enterprise.ai.runtime.registry;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeEmbeddedDispatchService {

    static final String EMBEDDED_EXECUTE_PATH = "/eaf/runtime/embedded/execute";

    private final RuntimeRegistryService registryService;
    private final RestTemplate restTemplate;

    @Autowired
    public RuntimeEmbeddedDispatchService(RuntimeRegistryService registryService,
                                          RestTemplateBuilder restTemplateBuilder) {
        this(registryService, restTemplateBuilder.build());
    }

    public RuntimeEmbeddedDispatchResult dispatch(RuntimeEmbeddedDispatchRequest request) {
        validateRequest(request);
        RuntimeRegistryEntry instance = registryService.findRuntimeInstance(request.projectCode(), request.instanceId());
        validateInstance(instance);

        String dispatchUrl = trimTrailingSlash(instance.baseUrl()) + EMBEDDED_EXECUTE_PATH;
        try {
            ResponseEntity<RuntimeEmbeddedDispatchResult> response = restTemplate.postForEntity(
                    dispatchUrl,
                    toRemoteRequest(request),
                    RuntimeEmbeddedDispatchResult.class);
            RuntimeEmbeddedDispatchResult remote = response.getBody();
            if (remote == null) {
                return failure(request, "EMPTY_RESPONSE", "业务系统 Embedded Runtime 返回为空");
            }
            return new RuntimeEmbeddedDispatchResult(
                    remote.success(),
                    remote.answer(),
                    request.projectCode(),
                    request.instanceId(),
                    dispatchUrl,
                    remote.steps(),
                    remote.metadata(),
                    remote.errorCode(),
                    remote.errorMessage());
        } catch (RestClientResponseException ex) {
            return failure(request, "REMOTE_HTTP_" + ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            return failure(request, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private Map<String, Object> toRemoteRequest(RuntimeEmbeddedDispatchRequest request) {
        return Map.of(
                "agentKey", request.agentKey(),
                "message", request.message() == null ? "" : request.message(),
                "sessionId", request.sessionId() == null ? "" : request.sessionId(),
                "userId", request.userId() == null ? "" : request.userId(),
                "context", request.context() == null ? Map.of() : request.context(),
                "graphSpec", request.graphSpec() == null ? Map.of() : request.graphSpec());
    }

    private void validateRequest(RuntimeEmbeddedDispatchRequest request) {
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

    private void validateInstance(RuntimeRegistryEntry instance) {
        if (!"ONLINE".equalsIgnoreCase(instance.status())) {
            throw new IllegalStateException("Runtime 实例不在线: " + instance.status());
        }
        if (!StringUtils.hasText(instance.baseUrl())) {
            throw new IllegalStateException("Runtime 实例缺少 baseUrl");
        }
        if (isCapabilityHost(instance)) {
            throw new IllegalStateException("Capability Host 只能提供业务能力调用，不能作为 Agent Runtime 执行目标");
        }
        if (instance.policyDisabled()) {
            throw new IllegalStateException("Runtime 实例已被治理策略禁用: " + instance.policyMessage());
        }
        if (Boolean.FALSE.equals(instance.allowEmbeddedExecution())) {
            throw new IllegalStateException("Runtime 实例未被允许执行 Embedded Runtime: " + instance.policyMessage());
        }
    }

    private boolean isCapabilityHost(RuntimeRegistryEntry instance) {
        if ("CAPABILITY_HOST".equalsIgnoreCase(instance.runtimeRole())) {
            return true;
        }
        String placement = instance.runtimePlacement();
        if ("CAPABILITY_HOST".equalsIgnoreCase(placement)) {
            return true;
        }
        List<String> runtimeTypes = instance.runtimeTypes();
        return runtimeTypes.stream()
                .map(type -> type == null ? "" : type.toUpperCase(Locale.ROOT))
                .anyMatch(type -> type.contains("CAPABILITY_HOST"));
    }

    private RuntimeEmbeddedDispatchResult failure(RuntimeEmbeddedDispatchRequest request,
                                                  String errorCode,
                                                  String errorMessage) {
        return RuntimeEmbeddedDispatchResult.failure(
                request.projectCode(),
                request.instanceId(),
                errorCode,
                errorMessage);
    }

    private String trimTrailingSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
