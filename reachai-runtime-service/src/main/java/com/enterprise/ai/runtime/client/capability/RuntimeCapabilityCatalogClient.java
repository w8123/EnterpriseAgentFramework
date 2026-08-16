package com.enterprise.ai.runtime.client.capability;

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

    Map<String, Object> getToolDefinition(String qualifiedName);

    Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request);

    Map<String, Object> getCompositionDefinition(String qualifiedName);

    Map<String, Object> getProject(String projectCode);

    Map<String, Object> getProjectById(Long projectId);

    List<Map<String, Object>> listProjectTools(Long projectId);

    Map<String, Object> projectReadinessFacts(Long projectId);

}
