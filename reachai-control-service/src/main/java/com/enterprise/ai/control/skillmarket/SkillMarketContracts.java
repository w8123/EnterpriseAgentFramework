package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportResult;
import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector.BundleCandidate;

import java.time.LocalDateTime;
import java.util.List;

public final class SkillMarketContracts {

    private SkillMarketContracts() {
    }

    public record SearchRequest(String query, String view, String owner, int limit) {
    }

    public record SearchResult(
            String provider,
            String mode,
            String query,
            String view,
            int count,
            boolean authenticatedUpstream,
            String message,
            List<MarketSkill> items) {
    }

    public record MarketSkill(
            String id,
            String slug,
            String name,
            String description,
            String source,
            String sourceType,
            long installs,
            String installUrl,
            String marketUrl,
            boolean duplicate,
            String trustLevel,
            boolean importable) {

        public MarketSkill withTrust(String trustLevel, boolean importable) {
            return new MarketSkill(id, slug, name, description, source, sourceType, installs,
                    installUrl, marketUrl, duplicate, trustLevel, importable);
        }
    }

    public record MarketSource(
            Long id,
            String sourceKey,
            String displayName,
            String sourceType,
            String baseUrl,
            String repositoryUrl,
            String trustLevel,
            String status,
            boolean supportsSearch,
            boolean supportsImport,
            boolean official,
            String description,
            int displayOrder,
            LocalDateTime updatedAt) {
    }

    public record ProbeRequest(
            String sourceUrl,
            String marketplaceProvider,
            String marketplaceSkillId,
            String expectedSkillName) {
    }

    public record RepositoryMetadata(
            String owner,
            String repository,
            String repositoryUrl,
            String defaultBranch,
            String commitSha,
            String description,
            String license,
            long stars,
            boolean archived,
            LocalDateTime updatedAt) {
    }

    public record ProbeResult(
            String schema,
            String provider,
            String marketplaceSkillId,
            RepositoryMetadata repository,
            String bundleSourceSha256,
            long bundleSize,
            int archiveFileCount,
            int candidateCount,
            String suggestedSourceRoot,
            List<String> warnings,
            List<BundleCandidate> candidates) {
    }

    public record ImportRequest(
            String sourceUrl,
            String commitSha,
            String sourceRoot,
            String expectedSelectedSourceSha256,
            String marketplaceProvider,
            String marketplaceSkillId,
            String publisher,
            String version,
            String displayName,
            String visibility,
            String projectCode) {
    }

    public record MarketImportResult(
            ImportResult imported,
            ImportOrigin origin) {
    }

    public record ImportOrigin(
            Long id,
            String providerKey,
            String marketplaceSkillId,
            String repository,
            String repositoryUrl,
            String sourceCommitSha,
            String sourceRoot,
            String bundleSourceSha256,
            String selectedSourceSha256,
            Long skillId,
            Long skillVersionId,
            String publisher,
            String name,
            String version,
            String visibility,
            String projectCode,
            String importedBy,
            LocalDateTime createdAt) {
    }
}
