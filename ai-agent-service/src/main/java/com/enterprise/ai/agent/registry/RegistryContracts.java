package com.enterprise.ai.agent.registry;

import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;

import java.util.List;
import java.util.Map;

public final class RegistryContracts {

    private RegistryContracts() {
    }

    public record ProjectRegisterRequest(
            String projectCode,
            String name,
            String environment,
            String owner,
            String visibility,
            String baseUrl,
            String contextPath,
            String appKey,
            String appSecret,
            Map<String, Object> metadata
    ) {
    }

    public record InstanceHeartbeatRequest(
            String instanceId,
            String baseUrl,
            String host,
            Integer port,
            String appVersion,
            String sdkVersion,
            Map<String, Object> metadata
    ) {
    }

    public record CapabilitySyncRequest(
            String syncId,
            String source,
            Boolean apply,
            List<CapabilityRegistration> capabilities
    ) {
    }

    public record CapabilityRegistration(
            String name,
            String title,
            String description,
            String httpMethod,
            String baseUrl,
            String contextPath,
            String endpointPath,
            String requestBodyType,
            String responseType,
            String sideEffect,
            Boolean enabled,
            Boolean agentVisible,
            Boolean lightweightEnabled,
            String visibility,
            List<ToolDefinitionParameter> parameters,
            Map<String, Object> metadata
    ) {
    }

    public record RegistryProjectResponse(
            Long projectId,
            String projectCode,
            String name,
            String environment,
            String visibility
    ) {
    }

    public record CapabilityDiffItem(
            String qualifiedName,
            String name,
            String changeType,
            Long existingToolId,
            String storageName,
            List<FieldDiff> fieldDiffs,
            Map<String, Object> impact
    ) {
    }

    public record CapabilitySyncResponse(
            String syncId,
            Long projectId,
            String projectCode,
            int received,
            int added,
            int changed,
            int unchanged,
            int applied,
            List<CapabilityDiffItem> items
    ) {
    }

    public record FieldDiff(
            String field,
            Object oldValue,
            Object newValue
    ) {
    }

    public record CapabilitySnapshotDTO(
            Long id,
            Long projectId,
            String projectCode,
            String syncId,
            String source,
            String status,
            Integer received,
            Integer added,
            Integer changed,
            Integer unchanged,
            Integer deleted,
            String createdAt,
            String updatedAt
    ) {
    }

    public record CapabilityDiffItemDTO(
            Long id,
            Long snapshotId,
            String syncId,
            String projectCode,
            String qualifiedName,
            String name,
            String storageName,
            String changeType,
            Long existingToolId,
            String fieldDiffJson,
            String impactJson,
            String reviewStatus,
            String reviewNote
    ) {
    }

    public record CapabilityReviewRequest(
            String action,
            String operator,
            String note
    ) {
    }
}
