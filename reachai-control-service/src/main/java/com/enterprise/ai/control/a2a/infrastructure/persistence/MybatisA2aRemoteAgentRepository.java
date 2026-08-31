package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentHealth;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCapabilities;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCardSnapshot;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteProtocolSkill;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus;
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
public class MybatisA2aRemoteAgentRepository implements A2aRemoteAgentRepository {

    private static final TypeReference<List<A2aRemoteInterface>> INTERFACES = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<A2aRemoteProtocolSkill>> SKILLS = new TypeReference<>() { };

    private final A2aRemoteAgentMapper agentMapper;
    private final A2aRemoteAgentRevisionMapper revisionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean existsByKey(String remoteAgentKey) {
        return agentMapper.selectCount(Wrappers.<A2aRemoteAgentEntity>lambdaQuery()
                .eq(A2aRemoteAgentEntity::getRemoteAgentKey, remoteAgentKey)) > 0;
    }

    @Override
    public boolean existsByCardUrlHash(String tenantScope, String cardUrlSha256) {
        return agentMapper.selectCount(Wrappers.<A2aRemoteAgentEntity>lambdaQuery()
                .eq(A2aRemoteAgentEntity::getTenantScope, tenantScope == null ? "" : tenantScope)
                .eq(A2aRemoteAgentEntity::getCardUrlSha256, cardUrlSha256)) > 0;
    }

    @Override
    public Optional<A2aRemoteAgent> findById(long id) {
        return Optional.ofNullable(agentMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<A2aRemoteAgentRevision> findRevision(long remoteAgentId, long revisionId) {
        return Optional.ofNullable(revisionMapper.selectOne(
                        Wrappers.<A2aRemoteAgentRevisionEntity>lambdaQuery()
                                .eq(A2aRemoteAgentRevisionEntity::getRemoteAgentId, remoteAgentId)
                                .eq(A2aRemoteAgentRevisionEntity::getId, revisionId)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public Optional<A2aRemoteAgentRevision> findRevisionByHash(long remoteAgentId, String cardSha256) {
        return Optional.ofNullable(revisionMapper.selectOne(
                        Wrappers.<A2aRemoteAgentRevisionEntity>lambdaQuery()
                                .eq(A2aRemoteAgentRevisionEntity::getRemoteAgentId, remoteAgentId)
                                .eq(A2aRemoteAgentRevisionEntity::getAgentCardSha256, cardSha256)
                                .orderByDesc(A2aRemoteAgentRevisionEntity::getRevisionNo)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public Optional<A2aRemoteAgentRevision> findLatestRevision(long remoteAgentId) {
        return Optional.ofNullable(revisionMapper.selectOne(
                        Wrappers.<A2aRemoteAgentRevisionEntity>lambdaQuery()
                                .eq(A2aRemoteAgentRevisionEntity::getRemoteAgentId, remoteAgentId)
                                .orderByDesc(A2aRemoteAgentRevisionEntity::getRevisionNo)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public List<A2aRemoteAgentRevision> findRevisions(long remoteAgentId) {
        return revisionMapper.selectList(Wrappers.<A2aRemoteAgentRevisionEntity>lambdaQuery()
                        .eq(A2aRemoteAgentRevisionEntity::getRemoteAgentId, remoteAgentId)
                        .orderByDesc(A2aRemoteAgentRevisionEntity::getRevisionNo))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Page findPage(String search, String status, String health, int limit, int offset) {
        var query = Wrappers.<A2aRemoteAgentEntity>lambdaQuery()
                .eq(status != null, A2aRemoteAgentEntity::getStatus, status)
                .eq(health != null, A2aRemoteAgentEntity::getHealthStatus, health)
                .and(search != null && !search.isBlank(), nested -> nested
                        .like(A2aRemoteAgentEntity::getRemoteAgentKey, search.trim())
                        .or()
                        .like(A2aRemoteAgentEntity::getDisplayName, search.trim()))
                .orderByDesc(A2aRemoteAgentEntity::getUpdatedAt)
                .orderByDesc(A2aRemoteAgentEntity::getId);
        long total = agentMapper.selectCount(query);
        query.last("LIMIT " + limit + " OFFSET " + offset);
        return new Page(agentMapper.selectList(query).stream().map(this::toDomain).toList(), total);
    }

    @Override
    public A2aRemoteAgent save(A2aRemoteAgent remoteAgent) {
        A2aRemoteAgentEntity entity = toEntity(remoteAgent);
        try {
            if (entity.getId() == null) {
                agentMapper.insert(entity);
            } else if (agentMapper.updateById(entity) != 1) {
                throw new A2aDomainException("A2A_REMOTE_AGENT_VERSION_CONFLICT",
                        "remote Agent changed concurrently; reload and retry");
            }
        } catch (DuplicateKeyException exception) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_CONFLICT",
                    "remoteAgentKey or scoped Agent Card URL is already registered");
        }
        A2aRemoteAgentEntity reloaded = agentMapper.selectById(entity.getId());
        if (reloaded == null) throw persistenceFailure("remote Agent");
        return toDomain(reloaded);
    }

    @Override
    public A2aRemoteAgentRevision saveRevision(A2aRemoteAgentRevision revision) {
        A2aRemoteAgentRevisionEntity entity = toEntity(revision);
        try {
            if (entity.getId() == null) {
                revisionMapper.insert(entity);
            } else {
                var update = Wrappers.<A2aRemoteAgentRevisionEntity>lambdaUpdate()
                        .eq(A2aRemoteAgentRevisionEntity::getId, entity.getId())
                        .eq(A2aRemoteAgentRevisionEntity::getRemoteAgentId, entity.getRemoteAgentId())
                        .eq(A2aRemoteAgentRevisionEntity::getAgentCardSha256,
                                entity.getAgentCardSha256())
                        .eq(A2aRemoteAgentRevisionEntity::getReviewStatus,
                                A2aRemoteRevisionReviewStatus.PENDING.name())
                        .set(A2aRemoteAgentRevisionEntity::getReviewStatus, entity.getReviewStatus())
                        .set(A2aRemoteAgentRevisionEntity::getReviewedBy, entity.getReviewedBy())
                        .set(A2aRemoteAgentRevisionEntity::getReviewedAt, entity.getReviewedAt());
                if (revisionMapper.update(null, update) != 1) {
                    throw new A2aDomainException("A2A_REMOTE_REVISION_CONFLICT",
                            "remote Agent revision changed concurrently or immutable content drifted");
                }
            }
        } catch (DuplicateKeyException exception) {
            throw new A2aDomainException("A2A_REMOTE_REVISION_CONFLICT",
                    "remote Agent revision number already exists");
        }
        A2aRemoteAgentRevisionEntity reloaded = revisionMapper.selectById(entity.getId());
        if (reloaded == null) throw persistenceFailure("remote Agent revision");
        return toDomain(reloaded);
    }

    @Override
    public void supersedePendingRevisions(long remoteAgentId, Long excludingRevisionId) {
        var update = Wrappers.<A2aRemoteAgentRevisionEntity>lambdaUpdate()
                .eq(A2aRemoteAgentRevisionEntity::getRemoteAgentId, remoteAgentId)
                .eq(A2aRemoteAgentRevisionEntity::getReviewStatus,
                        A2aRemoteRevisionReviewStatus.PENDING.name())
                .ne(excludingRevisionId != null, A2aRemoteAgentRevisionEntity::getId,
                        excludingRevisionId)
                .set(A2aRemoteAgentRevisionEntity::getReviewStatus,
                        A2aRemoteRevisionReviewStatus.SUPERSEDED.name());
        revisionMapper.update(null, update);
    }

    @Override
    public void supersedeApprovedRevisions(long remoteAgentId, long excludingRevisionId) {
        revisionMapper.update(null, Wrappers.<A2aRemoteAgentRevisionEntity>lambdaUpdate()
                .eq(A2aRemoteAgentRevisionEntity::getRemoteAgentId, remoteAgentId)
                .eq(A2aRemoteAgentRevisionEntity::getReviewStatus,
                        A2aRemoteRevisionReviewStatus.APPROVED.name())
                .ne(A2aRemoteAgentRevisionEntity::getId, excludingRevisionId)
                .set(A2aRemoteAgentRevisionEntity::getReviewStatus,
                        A2aRemoteRevisionReviewStatus.SUPERSEDED.name()));
    }

    @Override
    public void rebindCredential(long oldCredentialId, long newCredentialId) {
        agentMapper.update(null, Wrappers.<A2aRemoteAgentEntity>lambdaUpdate()
                .eq(A2aRemoteAgentEntity::getCredentialId, oldCredentialId)
                .set(A2aRemoteAgentEntity::getCredentialId, newCredentialId));
    }

    @Override
    public int nextRevisionNo(long remoteAgentId) {
        A2aRemoteAgentRevisionEntity latest = revisionMapper.selectOne(
                Wrappers.<A2aRemoteAgentRevisionEntity>lambdaQuery()
                        .select(A2aRemoteAgentRevisionEntity::getRevisionNo)
                        .eq(A2aRemoteAgentRevisionEntity::getRemoteAgentId, remoteAgentId)
                        .orderByDesc(A2aRemoteAgentRevisionEntity::getRevisionNo)
                        .last("LIMIT 1"));
        return latest == null || latest.getRevisionNo() == null ? 1 : latest.getRevisionNo() + 1;
    }

    private A2aRemoteAgent toDomain(A2aRemoteAgentEntity entity) {
        return new A2aRemoteAgent(
                entity.getId(), entity.getRemoteAgentKey(), entity.getDisplayName(),
                entity.getTenantScope(), entity.getCardUrl(), entity.getCardUrlSha256(),
                entity.getTrustProfileId(), entity.getCredentialId(), entity.getCurrentRevisionId(),
                entity.getPreferredInterfaceKey(), entity.getPreferredSecuritySchemeKey(),
                A2aRemoteAgentStatus.parse(entity.getStatus()),
                A2aRemoteAgentHealth.parse(entity.getHealthStatus()),
                entity.getConsecutiveHealthFailures() == null ? 0 : entity.getConsecutiveHealthFailures(),
                entity.getLastDiscoveredAt(), entity.getLastHealthCheckedAt(),
                entity.getLastHealthSummary(), entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getCreatedBy(), entity.getUpdatedBy(), entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private A2aRemoteAgentEntity toEntity(A2aRemoteAgent value) {
        A2aRemoteAgentEntity entity = new A2aRemoteAgentEntity();
        entity.setId(value.id());
        entity.setRemoteAgentKey(value.remoteAgentKey());
        entity.setDisplayName(value.displayName());
        entity.setTenantScope(value.tenantScope());
        entity.setCardUrl(value.cardUrl());
        entity.setCardUrlSha256(value.cardUrlSha256());
        entity.setTrustProfileId(value.trustProfileId());
        entity.setCredentialId(value.credentialId());
        entity.setCurrentRevisionId(value.currentRevisionId());
        entity.setPreferredInterfaceKey(value.preferredInterfaceKey());
        entity.setPreferredSecuritySchemeKey(value.preferredSecuritySchemeKey());
        entity.setStatus(value.status().name());
        entity.setHealthStatus(value.healthStatus().name());
        entity.setConsecutiveHealthFailures(value.consecutiveHealthFailures());
        entity.setLastDiscoveredAt(value.lastDiscoveredAt());
        entity.setLastHealthCheckedAt(value.lastHealthCheckedAt());
        entity.setLastHealthSummary(value.lastHealthSummary());
        entity.setVersion(value.version());
        entity.setCreatedBy(value.createdBy());
        entity.setUpdatedBy(value.updatedBy());
        entity.setCreatedAt(value.createdAt());
        entity.setUpdatedAt(value.updatedAt());
        return entity;
    }

    private A2aRemoteAgentRevision toDomain(A2aRemoteAgentRevisionEntity entity) {
        A2aRemoteCardSnapshot card = new A2aRemoteCardSnapshot(
                entity.getName(), entity.getDescription(), entity.getProviderOrganization(),
                entity.getProviderUrl(), entity.getDocumentationUrl(), entity.getIconUrl(),
                entity.getAgentVersion(),
                read(entity.getSupportedInterfacesJson(), INTERFACES, "supportedInterfacesJson"),
                read(entity.getCapabilitiesJson(), A2aRemoteCapabilities.class, "capabilitiesJson"),
                read(entity.getDefaultInputModesJson(), STRINGS, "defaultInputModesJson"),
                read(entity.getDefaultOutputModesJson(), STRINGS, "defaultOutputModesJson"),
                read(entity.getProtocolSkillsJson(), SKILLS, "protocolSkillsJson"),
                entity.getSecuritySchemesJson(), entity.getSecurityRequirementsJson(),
                entity.getAgentCardJson(), entity.getAgentCardSha256(), entity.getSignatureStatus(),
                entity.getSigningKeyId());
        return new A2aRemoteAgentRevision(
                entity.getId(), entity.getRemoteAgentId(), entity.getRevisionNo(), card,
                entity.getTlsIdentitySha256(), entity.getNetworkEvidenceJson(), entity.getHttpEtag(),
                entity.getHttpLastModified(), A2aRemoteRevisionReviewStatus.parse(entity.getReviewStatus()),
                entity.getDiscoveredAt(), entity.getReviewedBy(), entity.getReviewedAt(),
                entity.getCreatedAt());
    }

    private A2aRemoteAgentRevisionEntity toEntity(A2aRemoteAgentRevision value) {
        A2aRemoteAgentRevisionEntity entity = new A2aRemoteAgentRevisionEntity();
        A2aRemoteCardSnapshot card = value.card();
        entity.setId(value.id());
        entity.setRemoteAgentId(value.remoteAgentId());
        entity.setRevisionNo(value.revisionNo());
        entity.setName(card.name());
        entity.setDescription(card.description());
        entity.setProviderOrganization(card.providerOrganization());
        entity.setProviderUrl(card.providerUrl());
        entity.setDocumentationUrl(card.documentationUrl());
        entity.setIconUrl(card.iconUrl());
        entity.setAgentVersion(card.agentVersion());
        entity.setSupportedInterfacesJson(write(card.supportedInterfaces(), "supportedInterfaces"));
        entity.setCapabilitiesJson(write(card.capabilities(), "capabilities"));
        entity.setDefaultInputModesJson(write(card.defaultInputModes(), "defaultInputModes"));
        entity.setDefaultOutputModesJson(write(card.defaultOutputModes(), "defaultOutputModes"));
        entity.setProtocolSkillsJson(write(card.protocolSkills(), "protocolSkills"));
        entity.setSecuritySchemesJson(card.securitySchemesJson());
        entity.setSecurityRequirementsJson(card.securityRequirementsJson());
        entity.setAgentCardJson(card.agentCardJson());
        entity.setAgentCardSha256(card.agentCardSha256());
        entity.setSignatureStatus(card.signatureStatus());
        entity.setSigningKeyId(card.signingKeyId());
        entity.setTlsIdentitySha256(value.tlsIdentitySha256());
        entity.setNetworkEvidenceJson(value.networkEvidenceJson());
        entity.setHttpEtag(value.httpEtag());
        entity.setHttpLastModified(value.httpLastModified());
        entity.setReviewStatus(value.reviewStatus().name());
        entity.setDiscoveredAt(value.discoveredAt());
        entity.setReviewedBy(value.reviewedBy());
        entity.setReviewedAt(value.reviewedAt());
        entity.setCreatedAt(value.createdAt());
        return entity;
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw dataInvalid(field, exception);
        }
    }

    private <T> T read(String json, Class<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw dataInvalid(field, exception);
        }
    }

    private String write(Object value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw dataInvalid(field, exception);
        }
    }

    private A2aDomainException dataInvalid(String field, Exception cause) {
        A2aDomainException failure = new A2aDomainException("A2A_REMOTE_AGENT_DATA_INVALID",
                "remote Agent " + field + " is invalid");
        failure.initCause(cause);
        return failure;
    }

    private A2aDomainException persistenceFailure(String subject) {
        return new A2aDomainException("A2A_REMOTE_AGENT_PERSISTENCE_FAILED",
                subject + " could not be reloaded after persistence");
    }
}
