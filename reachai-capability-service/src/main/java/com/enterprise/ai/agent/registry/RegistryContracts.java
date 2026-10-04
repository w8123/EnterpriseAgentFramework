package com.enterprise.ai.agent.registry;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.databind.JsonNode;

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
            List<String> allowedOrigins,
            List<String> allowedAgentIds,
            Integer tokenTtlSeconds,
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

    public record InstanceHeartbeatResponse(
            ProjectInstanceEntity instance
    ) {
    }

    public record CapabilitySyncRequest(
            String syncId,
            String source,
            Boolean apply,
            List<CapabilityRegistration> capabilities,
            List<HttpApiRegistration> httpApis
    ) {
        /** Compatibility constructor for source clients that predate the HTTP API inventory. */
        public CapabilitySyncRequest(String syncId, String source, Boolean apply,
                                     List<CapabilityRegistration> capabilities) {
            this(syncId, source, apply, capabilities, null);
        }
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
            List<ToolDefinitionParameter> parameters,
            Map<String, Object> metadata
    ) {
    }

    public record RegistryProjectResponse(
            Long projectId,
            String projectCode,
            String name,
            String environment,
            String visibility,
            String appKey,
            String appSecret
    ) {
    }

    /** A separately scoped HTTP API source fact; it is never a business-method declaration. */
    public record HttpApiRegistration(
            String sourceKey,
            String sourceLocation,
            String httpMethod,
            String contextPath,
            String endpointPath,
            List<String> consumes,
            List<String> produces,
            List<HttpApiMappingConditionRegistration> mappingConditions,
            List<HttpApiParameterRegistration> parameters,
            HttpApiRequestBodyRegistration requestBody,
            List<HttpApiResponseRegistration> responses,
            String authenticationState,
            List<String> authenticationSchemes,
            List<String> requiredHeaderNames,
            String sideEffect
    ) {
    }

    public record HttpApiMappingConditionRegistration(
            String kind,
            String name,
            String operator,
            String value
    ) {
    }

    public record HttpApiParameterRegistration(
            String name,
            String location,
            Boolean required,
            JsonNode schema,
            List<String> contentTypes
    ) {
    }

    /** BODY is fixed here so a wire client cannot quietly treat an unknown parameter as a body. */
    public record HttpApiRequestBodyRegistration(
            String location,
            Boolean required,
            JsonNode schema,
            List<String> contentTypes
    ) {
    }

    public record HttpApiResponseRegistration(
            String status,
            JsonNode schema,
            List<String> contentTypes
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
            List<CapabilityDiffItem> items,
            HttpApiSyncSummary httpApis
    ) {
        public CapabilitySyncResponse(String syncId, Long projectId, String projectCode,
                                      int received, int added, int changed, int unchanged, int applied,
                                      List<CapabilityDiffItem> items) {
            this(syncId, projectId, projectCode, received, added, changed, unchanged, applied, items,
                    new HttpApiSyncSummary(false, 0, 0, 0));
        }
    }

    /** Separate receipt summary for the HTTP API inventory within the same registry sync. */
    public record HttpApiSyncSummary(
            boolean supported,
            int received,
            int observed,
            int removed
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
            String reviewNote,
            Boolean rollbackAvailable
    ) {
    }

    public record CapabilityReviewRequest(
            String action,
            String operator,
            String note
    ) {
    }

    /**
     * SDK 运行时拉取的「接口/参数说明来源」子集；服务端已剔除仅离线源码可用的项（如 JAVADOC）。
     */
    public record SdkCapabilityDescriptionSettings(
            List<String> descriptionSourceOrder,
            List<String> paramDescriptionSourceOrder,
            Map<String, Boolean> descriptionSourceEnabled,
            Map<String, Boolean> paramDescriptionSourceEnabled
    ) {
    }
}
