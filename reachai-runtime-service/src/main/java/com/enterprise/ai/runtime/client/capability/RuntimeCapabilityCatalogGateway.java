package com.enterprise.ai.runtime.client.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializes once, scrubs caller-controlled identity, then signs the exact bytes sent to Capability.
 */
@Component
public class RuntimeCapabilityCatalogGateway implements RuntimeCapabilityCatalogClient {

    private static final Set<String> IDENTITY_FIELDS = Set.of(
            "tenantId", "userId", "externalUserId", "globalUserId", "userName",
            "deptId", "deptName", "roles", "attributes");

    private final RuntimeCapabilityCatalogFeignClient transport;
    private final RuntimeCapabilityInternalAuthSigner signer;
    private final ObjectMapper objectMapper;

    public RuntimeCapabilityCatalogGateway(RuntimeCapabilityCatalogFeignClient transport,
                                           RuntimeCapabilityInternalAuthSigner signer,
                                           ObjectMapper objectMapper) {
        this.transport = transport;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> getToolDefinition(String qualifiedName) {
        return transport.getToolDefinition(qualifiedName);
    }

    @Override
    public Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request) {
        Map<String, Object> outbound = request == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
        Object marker = outbound.remove(TRUSTED_IDENTITY_ATTRIBUTE);
        WorkflowExecutionIdentity identity = marker instanceof WorkflowExecutionIdentity trusted
                ? trusted : null;

        Map<String, Object> context = stringMap(outbound.get("context"));
        IDENTITY_FIELDS.forEach(context::remove);

        String source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED;
        String tenantId = "";
        String userId = "";
        if (identity != null && identity.canResolveUserAcl()) {
            source = InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED;
            tenantId = normalized(identity.tenantId());
            userId = normalized(identity.userId());
            if (StringUtils.hasText(tenantId)) {
                context.put("tenantId", tenantId);
            }
            context.put("externalUserId", userId);
        }
        outbound.put("context", context);

        byte[] exactBody;
        try {
            exactBody = objectMapper.writeValueAsBytes(outbound);
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Capability Tool request serialization failed", serializationFailure);
        }
        Map<String, String> headers = signer.signToolExecute(
                qualifiedName, source, tenantId, userId, exactBody);
        return transport.executeTool(qualifiedName, headers, exactBody);
    }

    @Override
    public Map<String, Object> getCompositionDefinition(String qualifiedName) {
        return transport.getCompositionDefinition(qualifiedName);
    }

    @Override
    public Map<String, Object> getProject(String projectCode) {
        return transport.getProject(projectCode);
    }

    @Override
    public Map<String, Object> getProjectById(Long projectId) {
        return transport.getProjectById(projectId);
    }

    @Override
    public List<Map<String, Object>> listProjectTools(Long projectId) {
        return transport.listProjectTools(projectId);
    }

    @Override
    public Map<String, Object> projectReadinessFacts(Long projectId) {
        return transport.projectReadinessFacts(projectId);
    }

    private Map<String, Object> stringMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
