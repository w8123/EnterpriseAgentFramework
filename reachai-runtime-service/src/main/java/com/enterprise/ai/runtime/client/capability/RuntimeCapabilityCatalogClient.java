package com.enterprise.ai.runtime.client.capability;

import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;

import java.util.List;
import java.util.Map;

/**
 * Runtime-side Capability catalog boundary.
 *
 * <p>The implementation owns serialization and internal-service authentication for Tool execution.
 * Keeping this as a plain interface prevents public request bodies from supplying HMAC headers or
 * bypassing the trusted {@code WorkflowExecutionIdentity} marker.</p>
 */
public interface RuntimeCapabilityCatalogClient {

    /** Server-only marker; JSON objects with the same key are never trusted. */
    String TRUSTED_IDENTITY_ATTRIBUTE = "__runtimeTrustedExecutionIdentity";

    /** Server-only typed Eval policy marker; the gateway converts it into the signed wire body. */
    String TRUSTED_EVAL_CONTEXT_ATTRIBUTE = "__runtimeTrustedEvalExecutionContext";

    Map<String, Object> getToolDefinition(String qualifiedName);

    Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request);

    /**
     * Runtime Kernel V2 typed port. The default adapter keeps existing in-process implementations
     * source-compatible; the production gateway overrides this and transports Contract V1.
     */
    default CapabilityInvocationResponse invokeTool(String qualifiedName, Map<String, Object> request) {
        CapabilityInvocationRequest invocation = CapabilityInvocationRequest.fromRuntime(qualifiedName, request);
        return CapabilityInvocationResponse.fromLegacy(invocation, executeTool(qualifiedName, request));
    }

    Map<String, Object> getCompositionDefinition(String qualifiedName);

    Map<String, Object> getProject(String projectCode);

    Map<String, Object> getProjectById(Long projectId);

    List<Map<String, Object>> listProjectTools(Long projectId);

    Map<String, Object> projectReadinessFacts(Long projectId);

}
