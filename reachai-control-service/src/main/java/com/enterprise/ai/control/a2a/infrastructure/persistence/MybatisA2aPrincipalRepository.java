package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class MybatisA2aPrincipalRepository implements A2aPrincipalRepository {

    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final A2aPrincipalMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<A2aPrincipal> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<A2aPrincipal> lockActiveById(long id) {
        return Optional.ofNullable(mapper.lockActiveById(id)).map(this::toDomain);
    }

    @Override
    public Optional<A2aPrincipal> findActiveByCredential(long credentialId, long trustProfileId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<A2aPrincipalEntity>lambdaQuery()
                        .eq(A2aPrincipalEntity::getCredentialId, credentialId)
                        .eq(A2aPrincipalEntity::getTrustProfileId, trustProfileId)
                        .eq(A2aPrincipalEntity::getStatus, A2aPrincipalStatus.ACTIVE.name())
                        .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public Optional<A2aPrincipal> findActiveAnonymous(long trustProfileId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<A2aPrincipalEntity>lambdaQuery()
                        .isNull(A2aPrincipalEntity::getCredentialId)
                        .eq(A2aPrincipalEntity::getTrustProfileId, trustProfileId)
                        .eq(A2aPrincipalEntity::getStatus, A2aPrincipalStatus.ACTIVE.name())
                        .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public boolean existsByKey(String principalKey) {
        return mapper.selectCount(Wrappers.<A2aPrincipalEntity>lambdaQuery()
                .eq(A2aPrincipalEntity::getPrincipalKey, principalKey)) > 0;
    }

    @Override
    public Page findPage(String search, String status, int limit, int offset) {
        var query = Wrappers.<A2aPrincipalEntity>lambdaQuery()
                .eq(status != null, A2aPrincipalEntity::getStatus, status)
                .and(search != null && !search.isBlank(), nested -> nested
                        .like(A2aPrincipalEntity::getPrincipalKey, search.trim())
                        .or().like(A2aPrincipalEntity::getDisplayName, search.trim())
                        .or().like(A2aPrincipalEntity::getAuthenticatedSubject, search.trim()))
                .orderByDesc(A2aPrincipalEntity::getUpdatedAt)
                .orderByDesc(A2aPrincipalEntity::getId);
        long total = mapper.selectCount(query);
        query.last("LIMIT " + limit + " OFFSET " + offset);
        return new Page(mapper.selectList(query).stream().map(this::toDomain).toList(), total);
    }

    @Override
    public A2aPrincipal save(A2aPrincipal principal) {
        A2aPrincipalEntity entity = toEntity(principal);
        try {
            int affected = entity.getId() == null ? mapper.insert(entity) : mapper.updateById(entity);
            if (affected != 1) {
                throw new A2aDomainException("A2A_PRINCIPAL_VERSION_CONFLICT",
                        "principal changed concurrently; reload and retry");
            }
        } catch (DuplicateKeyException exception) {
            throw new A2aDomainException("A2A_PRINCIPAL_KEY_CONFLICT",
                    "principalKey is already in use");
        }
        A2aPrincipalEntity reloaded = mapper.selectById(entity.getId());
        if (reloaded == null) {
            throw new A2aDomainException("A2A_PRINCIPAL_PERSISTENCE_FAILED",
                    "principal could not be reloaded after persistence");
        }
        return toDomain(reloaded);
    }

    @Override
    public void markAuthenticated(long principalId, LocalDateTime authenticatedAt) {
        mapper.update(null, Wrappers.<A2aPrincipalEntity>lambdaUpdate()
                .eq(A2aPrincipalEntity::getId, principalId)
                .set(A2aPrincipalEntity::getLastAuthenticatedAt, authenticatedAt));
    }

    @Override
    public void rebindCredential(long oldCredentialId, long newCredentialId) {
        mapper.update(null, Wrappers.<A2aPrincipalEntity>lambdaUpdate()
                .eq(A2aPrincipalEntity::getCredentialId, oldCredentialId)
                .eq(A2aPrincipalEntity::getStatus, A2aPrincipalStatus.ACTIVE.name())
                .set(A2aPrincipalEntity::getCredentialId, newCredentialId)
                .setSql("version = version + 1"));
    }

    private A2aPrincipal toDomain(A2aPrincipalEntity entity) {
        return new A2aPrincipal(
                entity.getId(), entity.getPrincipalKey(), A2aPrincipalType.parse(entity.getPrincipalType()),
                entity.getDisplayName(), entity.getTenantScope(), entity.getAuthenticatedSubject(),
                entity.getTrustProfileId(), entity.getCredentialId(),
                readSet(entity.getScopesJson()), readMap(entity.getAttributesJson()),
                A2aPrincipalStatus.parse(entity.getStatus()), entity.getLastAuthenticatedAt(),
                entity.getVersion() == null ? 0 : entity.getVersion(), entity.getCreatedBy(),
                entity.getUpdatedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private A2aPrincipalEntity toEntity(A2aPrincipal principal) {
        A2aPrincipalEntity entity = new A2aPrincipalEntity();
        entity.setId(principal.id());
        entity.setPrincipalKey(principal.principalKey());
        entity.setPrincipalType(principal.principalType().name());
        entity.setDisplayName(principal.displayName());
        entity.setTenantScope(principal.tenantScope());
        entity.setAuthenticatedSubject(principal.authenticatedSubject());
        entity.setTrustProfileId(principal.trustProfileId());
        entity.setCredentialId(principal.credentialId());
        entity.setScopesJson(write(principal.scopes(), "scopes"));
        entity.setAttributesJson(write(principal.attributes(), "attributes"));
        entity.setStatus(principal.status().name());
        entity.setLastAuthenticatedAt(principal.lastAuthenticatedAt());
        entity.setVersion(principal.version());
        entity.setCreatedBy(principal.createdBy());
        entity.setUpdatedBy(principal.updatedBy());
        entity.setCreatedAt(principal.createdAt());
        entity.setUpdatedAt(principal.updatedAt());
        return entity;
    }

    private Set<String> readSet(String json) {
        return json == null || json.isBlank() ? Set.of() : read(json, STRING_SET, "scopesJson");
    }

    private Map<String, String> readMap(String json) {
        return json == null || json.isBlank() ? Map.of() : read(json, STRING_MAP, "attributesJson");
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new A2aDomainException("A2A_PRINCIPAL_DATA_INVALID",
                    "persisted " + field + " is invalid");
        }
    }

    private String write(Object value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new A2aDomainException("A2A_PRINCIPAL_SERIALIZATION_FAILED",
                    field + " could not be serialized");
        }
    }
}
