package com.enterprise.ai.capability.externalapi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ExternalApiCatalogViews {

    public record PageView(
            List<EntrySummary> records,
            long total,
            long current,
            long size,
            long pages) {
    }

    public record EntrySummary(
            Long id,
            String entryKey,
            String title,
            String summary,
            String categoryCode,
            List<String> tags,
            String authType,
            String pricingType,
            boolean httpsSupported,
            String corsPolicy,
            String publicationStatus,
            String verificationStatus,
            String specStatus,
            boolean featured,
            int popularityScore,
            LocalDateTime lastVerifiedAt,
            ProviderView provider,
            SourceView source) {
    }

    public record EntryDetail(
            EntrySummary entry,
            String description,
            String docsUrl,
            String termsUrl,
            String sourceEntryKey,
            String sourceCategory,
            List<VersionView> versions,
            List<VerificationView> verifications) {
    }

    public record ProviderView(
            Long id,
            String providerKey,
            String name,
            String homepageUrl,
            String logoUrl,
            boolean verified,
            String status) {
    }

    public record SourceView(
            Long id,
            String sourceKey,
            String name,
            String sourceType,
            String sourceUrl,
            String homepageUrl,
            String description,
            String syncStrategy,
            String trustLevel,
            String status,
            LocalDateTime lastSyncedAt,
            long entryCount) {
    }

    public record VersionView(
            Long id,
            String versionKey,
            String baseUrl,
            String openapiUrl,
            String specHash,
            String publicationStatus,
            LocalDateTime publishedAt,
            LocalDateTime deprecatedAt,
            List<OperationView> operations) {
    }

    public record OperationView(
            Long id,
            String operationKey,
            String operationId,
            String title,
            String description,
            String httpMethod,
            String path,
            String sideEffect,
            boolean authRequired,
            Object requestSchema,
            Object responseSchema,
            Map<String, Object> exampleParams,
            String status,
            String responseContentType,
            Integer responseStatus) {
        public OperationView(Long id, String operationKey, String operationId, String title, String description,
                String httpMethod, String path, String sideEffect, boolean authRequired, Object requestSchema,
                Object responseSchema, Map<String, Object> exampleParams, String status) {
            this(id, operationKey, operationId, title, description, httpMethod, path, sideEffect, authRequired,
                    requestSchema, responseSchema, exampleParams, status, null, null);
        }
    }

    public record VerificationView(
            Long id,
            Long versionId,
            String verificationType,
            String status,
            Integer httpStatus,
            Long latencyMs,
            String checkedUrl,
            String evidenceSummary,
            LocalDateTime checkedAt) {
    }

    public record CategoryView(String code, String name, long count) {
    }

    public record StatsView(
            long publishedApis,
            long verifiedApis,
            long apiSpecs,
            long projectIntegrations) {
    }

    public record IntegrationCreateRequest(
            Long projectId,
            String projectCode,
            Long versionId,
            List<Long> operationIds,
            String environment,
            String note) {
        public static IntegrationCreateRequest fromWire(Map<String, Object> wire) {
            if (wire == null || !java.util.Set.of("projectId", "projectCode", "versionId", "operationIds", "environment", "note").containsAll(wire.keySet())
                    || !(wire.get("operationIds") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 16 || ids.stream().distinct().count() != ids.size()
                    || !(wire.get("projectCode") instanceof String code) || code.isBlank()
                    || !(wire.get("environment") instanceof String environment) || environment.isBlank()
                    || wire.get("note") != null && !(wire.get("note") instanceof String)) throw new IllegalArgumentException("invalid catalog selection");
            return new IntegrationCreateRequest(positive(wire.get("projectId")), code, positive(wire.get("versionId")),
                    ids.stream().map(IntegrationCreateRequest::positive).toList(), environment, (String)wire.get("note"));
        }
        private static Long positive(Object value) {
            if (!(value instanceof Number number) || number.longValue() <= 0 || number.doubleValue() != number.longValue()) throw new IllegalArgumentException("invalid catalog id");
            return number.longValue();
        }
    }

    public record IntegrationStatusRequest(String status, String note) {
    }

    public record IntegrationView(
            Long id,
            Long projectId,
            String projectCode,
            String projectName,
            String environment,
            String status,
            String note,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            EntrySummary entry,
            VersionView version,
            List<OperationView> selectedOperations,
            boolean credentialRequired,
            List<ApiBindingView> apiBindings) {
        public IntegrationView(Long id, Long projectId, String projectCode, String projectName, String environment,
                String status, String note, LocalDateTime createdAt, LocalDateTime updatedAt, EntrySummary entry,
                VersionView version, List<OperationView> selectedOperations, boolean credentialRequired) {
            this(id, projectId, projectCode, projectName, environment, status, note, createdAt, updatedAt, entry,
                    version, selectedOperations, credentialRequired, List.of());
        }
    }

    public record ApiBindingView(Long operationId, Long apiId, String qualifiedName, String environment, String blockingReason) { }

    private ExternalApiCatalogViews() {
    }
}
