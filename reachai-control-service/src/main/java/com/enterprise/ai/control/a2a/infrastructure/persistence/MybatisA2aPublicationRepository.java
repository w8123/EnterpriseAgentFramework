package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.publication.A2aAgentCardSnapshot;
import com.enterprise.ai.control.a2a.domain.publication.A2aProtocolSkill;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublication;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationRevision;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationRevisionStatus;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MybatisA2aPublicationRepository implements A2aPublicationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<A2aProtocolSkill>> SKILL_LIST = new TypeReference<>() { };

    private final A2aPublicationMapper publicationMapper;
    private final A2aPublicationRevisionMapper revisionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<A2aPublication> findById(long id) {
        return Optional.ofNullable(publicationMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<A2aPublicationRevision> findRevision(long publicationId, long revisionId) {
        return Optional.ofNullable(revisionMapper.selectOne(
                        Wrappers.<A2aPublicationRevisionEntity>lambdaQuery()
                                .eq(A2aPublicationRevisionEntity::getPublicationId, publicationId)
                                .eq(A2aPublicationRevisionEntity::getId, revisionId)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public List<A2aPublicationRevision> findRevisions(long publicationId) {
        return revisionMapper.selectList(Wrappers.<A2aPublicationRevisionEntity>lambdaQuery()
                        .eq(A2aPublicationRevisionEntity::getPublicationId, publicationId)
                        .orderByDesc(A2aPublicationRevisionEntity::getRevisionNo))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public int nextRevisionNo(long publicationId) {
        A2aPublicationRevisionEntity latest = revisionMapper.selectOne(
                Wrappers.<A2aPublicationRevisionEntity>lambdaQuery()
                        .select(A2aPublicationRevisionEntity::getRevisionNo)
                        .eq(A2aPublicationRevisionEntity::getPublicationId, publicationId)
                        .orderByDesc(A2aPublicationRevisionEntity::getRevisionNo)
                        .last("LIMIT 1"));
        return latest == null || latest.getRevisionNo() == null ? 1 : latest.getRevisionNo() + 1;
    }

    @Override
    public boolean existsByPublicationKey(String publicationKey) {
        return publicationMapper.selectCount(Wrappers.<A2aPublicationEntity>lambdaQuery()
                .eq(A2aPublicationEntity::getPublicationKey, publicationKey)) > 0;
    }

    @Override
    public boolean existsByAgentScope(String agentId, String environment, String tenantScope) {
        return publicationMapper.selectCount(Wrappers.<A2aPublicationEntity>lambdaQuery()
                .eq(A2aPublicationEntity::getAgentId, agentId)
                .eq(A2aPublicationEntity::getEnvironment, environment)
                .eq(A2aPublicationEntity::getTenantScope, tenantScope == null ? "" : tenantScope)) > 0;
    }

    @Override
    public Page findPage(String search, String status, int limit, int offset) {
        var query = Wrappers.<A2aPublicationEntity>lambdaQuery()
                .eq(status != null, A2aPublicationEntity::getStatus, status)
                .and(search != null && !search.isBlank(), nested -> nested
                        .like(A2aPublicationEntity::getPublicationKey, search.trim())
                        .or()
                        .like(A2aPublicationEntity::getAgentId, search.trim())
                        .or()
                        .like(A2aPublicationEntity::getPublicHost, search.trim()))
                .orderByDesc(A2aPublicationEntity::getUpdatedAt)
                .orderByDesc(A2aPublicationEntity::getId);
        long total = publicationMapper.selectCount(query);
        query.last("LIMIT " + limit + " OFFSET " + offset);
        return new Page(publicationMapper.selectList(query).stream().map(this::toDomain).toList(), total);
    }

    @Override
    public A2aPublication save(A2aPublication publication) {
        A2aPublicationEntity entity = toEntity(publication);
        try {
            if (entity.getId() == null) {
                publicationMapper.insert(entity);
            } else if (publicationMapper.updateById(entity) != 1) {
                throw new A2aDomainException("A2A_PUBLICATION_VERSION_CONFLICT",
                        "publication changed concurrently; reload and retry");
            }
        } catch (DuplicateKeyException exception) {
            throw new A2aDomainException("A2A_PUBLICATION_CONFLICT",
                    "publication key, Agent scope, or public host is already in use");
        }
        A2aPublicationEntity reloaded = publicationMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw persistenceFailure("publication");
        }
        return toDomain(reloaded);
    }

    @Override
    public A2aPublicationRevision saveRevision(A2aPublicationRevision revision) {
        A2aPublicationRevisionEntity entity = toEntity(revision);
        try {
            if (entity.getId() == null) {
                revisionMapper.insert(entity);
            } else {
                var update = Wrappers.<A2aPublicationRevisionEntity>lambdaUpdate()
                        .eq(A2aPublicationRevisionEntity::getId, entity.getId())
                        .eq(A2aPublicationRevisionEntity::getPublicationId, entity.getPublicationId())
                        .eq(A2aPublicationRevisionEntity::getAgentCardSha256, entity.getAgentCardSha256())
                        .set(A2aPublicationRevisionEntity::getValidationSummaryJson,
                                entity.getValidationSummaryJson())
                        .set(A2aPublicationRevisionEntity::getStatus, entity.getStatus())
                        .set(A2aPublicationRevisionEntity::getPublishedAt, entity.getPublishedAt());
                if (revision.status() == A2aPublicationRevisionStatus.READY) {
                    update.in(A2aPublicationRevisionEntity::getStatus, "DRAFT", "READY");
                } else if (revision.status() == A2aPublicationRevisionStatus.PUBLISHED) {
                    update.in(A2aPublicationRevisionEntity::getStatus, "READY", "PUBLISHED");
                }
                if (revisionMapper.update(null, update) != 1) {
                    throw new A2aDomainException("A2A_PUBLICATION_REVISION_CONFLICT",
                            "publication revision changed concurrently or its immutable content drifted");
                }
            }
        } catch (DuplicateKeyException exception) {
            throw new A2aDomainException("A2A_PUBLICATION_REVISION_CONFLICT",
                    "the revision number or Agent Card snapshot already exists");
        }
        A2aPublicationRevisionEntity reloaded = revisionMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw persistenceFailure("publication revision");
        }
        return toDomain(reloaded);
    }

    @Override
    public Optional<PublishedCard> findPublishedCardByHost(String publicHost) {
        A2aPublicationEntity publication = publicationMapper.selectOne(
                Wrappers.<A2aPublicationEntity>lambdaQuery()
                        .eq(A2aPublicationEntity::getPublicHost, publicHost)
                        .eq(A2aPublicationEntity::getStatus, A2aPublicationStatus.PUBLISHED.name())
                        .isNotNull(A2aPublicationEntity::getCurrentRevisionId)
                        .last("LIMIT 1"));
        if (publication == null) {
            return Optional.empty();
        }
        A2aPublicationRevisionEntity revision = revisionMapper.selectOne(
                Wrappers.<A2aPublicationRevisionEntity>lambdaQuery()
                        .eq(A2aPublicationRevisionEntity::getId, publication.getCurrentRevisionId())
                        .eq(A2aPublicationRevisionEntity::getPublicationId, publication.getId())
                        .eq(A2aPublicationRevisionEntity::getStatus,
                                A2aPublicationRevisionStatus.PUBLISHED.name())
                        .last("LIMIT 1"));
        if (revision == null) {
            return Optional.empty();
        }
        return Optional.of(new PublishedCard(
                publication.getId(),
                revision.getId(),
                publication.getPublicationKey(),
                publication.getPublicHost(),
                publication.getAgentId(),
                revision.getAgentConfigVersionId(),
                publication.getTrustProfileId(),
                publication.getTenantScope(),
                publication.getEnvironment(),
                read(revision.getDefaultInputModesJson(), STRING_LIST, "defaultInputModesJson"),
                read(revision.getDefaultOutputModesJson(), STRING_LIST, "defaultOutputModesJson"),
                read(revision.getProtocolSkillsJson(), SKILL_LIST, "protocolSkillsJson"),
                revision.getAgentCardJson(),
                revision.getAgentCardSha256(),
                revision.getPublishedAt()));
    }

    private A2aPublication toDomain(A2aPublicationEntity entity) {
        return new A2aPublication(
                entity.getId(), entity.getPublicationKey(), entity.getAgentId(),
                entity.getProjectId(), entity.getProjectCode(),
                A2aEnvironment.parse(entity.getEnvironment()), entity.getTenantScope(),
                entity.getPublicHost(), entity.getTrustProfileId(), entity.getCurrentRevisionId(),
                A2aPublicationStatus.parse(entity.getStatus()),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getPublishedAt(), entity.getSuspendedAt(), entity.getCreatedBy(),
                entity.getUpdatedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private A2aPublicationEntity toEntity(A2aPublication publication) {
        A2aPublicationEntity entity = new A2aPublicationEntity();
        entity.setId(publication.id());
        entity.setPublicationKey(publication.publicationKey());
        entity.setAgentId(publication.agentId());
        entity.setProjectId(publication.projectId());
        entity.setProjectCode(publication.projectCode());
        entity.setEnvironment(publication.environment().name());
        entity.setTenantScope(publication.tenantScope());
        entity.setPublicHost(publication.publicHost());
        entity.setTrustProfileId(publication.trustProfileId());
        entity.setCurrentRevisionId(publication.currentRevisionId());
        entity.setStatus(publication.status().name());
        entity.setVersion(publication.version());
        entity.setPublishedAt(publication.publishedAt());
        entity.setSuspendedAt(publication.suspendedAt());
        entity.setCreatedBy(publication.createdBy());
        entity.setUpdatedBy(publication.updatedBy());
        entity.setCreatedAt(publication.createdAt());
        entity.setUpdatedAt(publication.updatedAt());
        return entity;
    }

    private A2aPublicationRevision toDomain(A2aPublicationRevisionEntity entity) {
        A2aAgentCardSnapshot snapshot = new A2aAgentCardSnapshot(
                entity.getAgentCardJson(), entity.getAgentCardSha256(),
                entity.getSecuritySchemesJson(), entity.getSecurityRequirementsJson(),
                entity.getProtocolSkillsJson(), entity.getDefaultInputModesJson(),
                entity.getDefaultOutputModesJson(), entity.getValidationSummaryJson());
        return new A2aPublicationRevision(
                entity.getId(), entity.getPublicationId(), entity.getRevisionNo(),
                entity.getAgentConfigVersionId(), entity.getAgentVersion(), entity.getName(),
                entity.getDescription(), entity.getProviderOrganization(), entity.getProviderUrl(),
                entity.getDocumentationUrl(), entity.getIconUrl(), entity.getPublicOrigin(),
                entity.getProtocolBasePath(), entity.getProtocolBinding(), entity.getProtocolVersion(),
                Boolean.TRUE.equals(entity.getStreamingSupported()),
                Boolean.TRUE.equals(entity.getPushNotificationsSupported()),
                Boolean.TRUE.equals(entity.getExtendedCardSupported()),
                read(entity.getDefaultInputModesJson(), STRING_LIST, "defaultInputModesJson"),
                read(entity.getDefaultOutputModesJson(), STRING_LIST, "defaultOutputModesJson"),
                read(entity.getProtocolSkillsJson(), SKILL_LIST, "protocolSkillsJson"),
                snapshot, entity.getSignatureStatus(), entity.getConformanceStatus(),
                A2aPublicationRevisionStatus.parse(entity.getStatus()), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getPublishedAt());
    }

    private A2aPublicationRevisionEntity toEntity(A2aPublicationRevision revision) {
        A2aPublicationRevisionEntity entity = new A2aPublicationRevisionEntity();
        entity.setId(revision.id());
        entity.setPublicationId(revision.publicationId());
        entity.setRevisionNo(revision.revisionNo());
        entity.setAgentConfigVersionId(revision.agentConfigVersionId());
        entity.setAgentVersion(revision.agentVersion());
        entity.setName(revision.name());
        entity.setDescription(revision.description());
        entity.setProviderOrganization(revision.providerOrganization());
        entity.setProviderUrl(revision.providerUrl());
        entity.setDocumentationUrl(revision.documentationUrl());
        entity.setIconUrl(revision.iconUrl());
        entity.setPublicOrigin(revision.publicOrigin());
        entity.setProtocolBasePath(revision.protocolBasePath());
        entity.setProtocolBinding(revision.protocolBinding());
        entity.setProtocolVersion(revision.protocolVersion());
        entity.setStreamingSupported(revision.streamingSupported());
        entity.setPushNotificationsSupported(revision.pushNotificationsSupported());
        entity.setExtendedCardSupported(revision.extendedCardSupported());
        entity.setDefaultInputModesJson(revision.cardSnapshot().defaultInputModesJson());
        entity.setDefaultOutputModesJson(revision.cardSnapshot().defaultOutputModesJson());
        entity.setProtocolSkillsJson(revision.cardSnapshot().protocolSkillsJson());
        entity.setSecuritySchemesJson(revision.cardSnapshot().securitySchemesJson());
        entity.setSecurityRequirementsJson(revision.cardSnapshot().securityRequirementsJson());
        entity.setAgentCardJson(revision.cardSnapshot().agentCardJson());
        entity.setAgentCardSha256(revision.cardSnapshot().agentCardSha256());
        entity.setSignatureStatus(revision.signatureStatus());
        entity.setConformanceStatus(revision.conformanceStatus());
        entity.setValidationSummaryJson(revision.cardSnapshot().validationSummaryJson());
        entity.setStatus(revision.status().name());
        entity.setCreatedBy(revision.createdBy());
        entity.setCreatedAt(revision.createdAt());
        entity.setPublishedAt(revision.publishedAt());
        return entity;
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            A2aDomainException error = new A2aDomainException("A2A_PUBLICATION_DATA_INVALID",
                    "persisted " + field + " is invalid");
            error.initCause(exception);
            throw error;
        }
    }

    private A2aDomainException persistenceFailure(String subject) {
        return new A2aDomainException("A2A_PUBLICATION_PERSISTENCE_FAILED",
                subject + " could not be reloaded after persistence");
    }
}
