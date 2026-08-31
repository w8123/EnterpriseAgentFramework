package com.enterprise.ai.control.agentskill;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

public final class AgentSkillContracts {

    private AgentSkillContracts() {
    }

    public record ImportCommand(
            String publisher,
            String version,
            String displayName,
            String visibility,
            String sourceType,
            String sourceRef,
            String operator,
            Long ownerUserId,
            String projectCode) {

        public ImportCommand(
                String publisher,
                String version,
                String displayName,
                String visibility,
                String sourceType,
                String sourceRef,
                String operator) {
            this(publisher, version, displayName, visibility, sourceType, sourceRef,
                    operator, null, null);
        }
    }

    public record ReviewCommand(
            String decision,
            String comment,
            JsonNode findings,
            String reviewer) {
    }

    public record SkillSummary(
            Long id,
            String publisher,
            String name,
            String displayName,
            String description,
            String visibility,
            Long ownerUserId,
            String projectCode,
            String status,
            Long latestVersionId,
            Long defaultVersionId,
            long versionCount,
            LocalDateTime updatedAt,
            String latestVersion,
            String latestVersionStatus,
            String defaultVersion) {

        public SkillSummary(
                Long id,
                String publisher,
                String name,
                String displayName,
                String description,
                String visibility,
                String status,
                Long latestVersionId,
                Long defaultVersionId,
                long versionCount,
                LocalDateTime updatedAt) {
            this(id, publisher, name, displayName, description, visibility, null, null, status,
                    latestVersionId, defaultVersionId, versionCount, updatedAt, null, null, null);
        }
    }

    public record SkillDetail(
            SkillSummary skill,
            List<VersionView> versions,
            SkillActions actions) {

        public SkillDetail(SkillSummary skill, List<VersionView> versions) {
            this(skill, versions, new SkillActions(false, false, false));
        }
    }

    public record SkillActions(boolean canReview, boolean canPublish, boolean canBind) {
    }

    public record SkillAccessCapabilities(
            boolean canImport,
            boolean canImportSharedOrPublic,
            long maxPackageBytes,
            long maxExpandedBytes,
            long maxSingleFileBytes,
            int maxFiles,
            int maxInstructionBytes,
            boolean scriptExecutionEnabled) {
    }

    public record ImportResult(
            SkillSummary skill,
            VersionView version,
            boolean created) {
    }

    /** Catalog-authoritative snapshot used at the Control → Runtime boundary. */
    public record BindingDescriptor(
            SkillSummary skill,
            VersionView version) {
    }

    public record VersionView(
            Long id,
            Long skillId,
            String version,
            String status,
            String sourceType,
            String sourceRef,
            String sourceSha256,
            String contentTreeSha256,
            Long artifactSize,
            String declaredLicense,
            String declaredCompatibility,
            boolean hasScripts,
            JsonNode frontmatter,
            JsonNode packageManifest,
            JsonNode validationReport,
            JsonNode riskReport,
            JsonNode compatibilityReport,
            String reviewedBy,
            LocalDateTime reviewedAt,
            String publishedBy,
            LocalDateTime publishedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record ReviewView(
            Long id,
            Long skillId,
            Long skillVersionId,
            String decision,
            String comment,
            JsonNode findings,
            String reviewer,
            LocalDateTime createdAt) {
    }

    public record FilePreview(
            String path,
            String kind,
            long size,
            String sha256,
            boolean previewable,
            boolean truncated,
            String content) {
    }
}
