package com.enterprise.ai.control.skillmarket;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.agentskill.AgentSkillCatalogService;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportResult;
import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ImportOrigin;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.MarketImportResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
public class SkillMarketImportWriter {

    private final AgentSkillCatalogService catalogService;
    private final SkillMarketImportMapper importMapper;

    public SkillMarketImportWriter(AgentSkillCatalogService catalogService,
                                   SkillMarketImportMapper importMapper) {
        this.catalogService = catalogService;
        this.importMapper = importMapper;
    }

    @Transactional
    public MarketImportResult importSelected(byte[] archive,
                                             ImportCommand command,
                                             OriginDraft origin) {
        ImportResult imported = catalogService.importPackage(archive, command);
        String fingerprint = fingerprint(origin, imported);
        SkillMarketImportEntity entity = importMapper.selectOne(
                Wrappers.<SkillMarketImportEntity>lambdaQuery()
                        .eq(SkillMarketImportEntity::getOriginFingerprint, fingerprint)
                        .last("LIMIT 1"));
        if (entity == null) {
            entity = toEntity(fingerprint, origin, imported);
            try {
                importMapper.insert(entity);
            } catch (DuplicateKeyException concurrentInsert) {
                entity = importMapper.selectOne(Wrappers.<SkillMarketImportEntity>lambdaQuery()
                        .eq(SkillMarketImportEntity::getOriginFingerprint, fingerprint)
                        .last("LIMIT 1"));
                if (entity == null) throw concurrentInsert;
            }
        }
        return new MarketImportResult(imported, view(entity));
    }

    private SkillMarketImportEntity toEntity(String fingerprint,
                                             OriginDraft origin,
                                             ImportResult imported) {
        SkillMarketImportEntity entity = new SkillMarketImportEntity();
        entity.setOriginFingerprint(fingerprint);
        entity.setProviderKey(origin.providerKey());
        entity.setMarketplaceSkillId(origin.marketplaceSkillId());
        entity.setRepository(origin.repository());
        entity.setRepositoryUrl(origin.repositoryUrl());
        entity.setSourceCommitSha(origin.sourceCommitSha());
        entity.setSourceRoot(origin.sourceRoot());
        entity.setBundleSourceSha256(origin.bundleSourceSha256());
        entity.setSelectedSourceSha256(imported.version().sourceSha256());
        entity.setSkillId(imported.skill().id());
        entity.setSkillVersionId(imported.version().id());
        entity.setPublisher(imported.skill().publisher());
        entity.setStandardName(imported.skill().name());
        entity.setVersion(imported.version().version());
        entity.setVisibility(imported.skill().visibility());
        entity.setProjectCode(imported.skill().projectCode());
        entity.setImportedByUserId(origin.importedByUserId());
        entity.setImportedBy(origin.importedBy());
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private String fingerprint(OriginDraft origin, ImportResult imported) {
        String value = String.join("\n",
                text(origin.providerKey()),
                text(origin.marketplaceSkillId()),
                text(origin.repository()),
                text(origin.sourceCommitSha()),
                text(origin.sourceRoot()),
                text(origin.bundleSourceSha256()),
                text(imported.version().sourceSha256()),
                String.valueOf(imported.version().id()));
        return AgentSkillPackageInspector.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static ImportOrigin view(SkillMarketImportEntity entity) {
        return new ImportOrigin(
                entity.getId(),
                entity.getProviderKey(),
                entity.getMarketplaceSkillId(),
                entity.getRepository(),
                entity.getRepositoryUrl(),
                entity.getSourceCommitSha(),
                entity.getSourceRoot(),
                entity.getBundleSourceSha256(),
                entity.getSelectedSourceSha256(),
                entity.getSkillId(),
                entity.getSkillVersionId(),
                entity.getPublisher(),
                entity.getStandardName(),
                entity.getVersion(),
                entity.getVisibility(),
                entity.getProjectCode(),
                entity.getImportedBy(),
                entity.getCreatedAt());
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record OriginDraft(
            String providerKey,
            String marketplaceSkillId,
            String repository,
            String repositoryUrl,
            String sourceCommitSha,
            String sourceRoot,
            String bundleSourceSha256,
            Long importedByUserId,
            String importedBy) {
    }
}
