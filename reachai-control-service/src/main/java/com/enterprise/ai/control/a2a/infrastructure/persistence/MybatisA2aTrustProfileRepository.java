package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.trust.A2aAuthorizationPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDataPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDelegatedIdentityPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import com.enterprise.ai.control.a2a.domain.trust.A2aPersonalMemoryPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfileStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class MybatisA2aTrustProfileRepository implements A2aTrustProfileRepository {

    private static final TypeReference<Set<A2aAuthenticationMethod>> AUTH_METHODS = new TypeReference<>() { };
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() { };

    private final A2aTrustProfileMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<A2aTrustProfile> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<A2aTrustProfile> findActiveById(long id) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<A2aTrustProfileEntity>lambdaQuery()
                        .eq(A2aTrustProfileEntity::getId, id)
                        .eq(A2aTrustProfileEntity::getStatus, A2aTrustProfileStatus.ACTIVE.name())
                        .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public boolean existsByProfileKey(String profileKey, Long excludingId) {
        return mapper.selectCount(Wrappers.<A2aTrustProfileEntity>lambdaQuery()
                .eq(A2aTrustProfileEntity::getProfileKey, profileKey)
                .ne(excludingId != null, A2aTrustProfileEntity::getId, excludingId)) > 0;
    }

    @Override
    public Page findPage(String search, String status, int limit, int offset) {
        var query = Wrappers.<A2aTrustProfileEntity>lambdaQuery()
                .eq(status != null, A2aTrustProfileEntity::getStatus, status)
                .and(search != null && !search.isBlank(), nested -> nested
                        .like(A2aTrustProfileEntity::getProfileKey, search.trim())
                        .or()
                        .like(A2aTrustProfileEntity::getName, search.trim()))
                .orderByDesc(A2aTrustProfileEntity::getUpdatedAt)
                .orderByDesc(A2aTrustProfileEntity::getId);
        long total = mapper.selectCount(query);
        query.last("LIMIT " + limit + " OFFSET " + offset);
        List<A2aTrustProfile> items = mapper.selectList(query).stream().map(this::toDomain).toList();
        return new Page(items, total);
    }

    @Override
    public A2aTrustProfile save(A2aTrustProfile profile) {
        A2aTrustProfileEntity entity = toEntity(profile);
        try {
            if (entity.getId() == null) {
                mapper.insert(entity);
            } else if (mapper.updateById(entity) != 1) {
                throw new A2aDomainException("A2A_TRUST_PROFILE_VERSION_CONFLICT",
                        "trust profile changed concurrently; reload and retry");
            }
        } catch (DuplicateKeyException exception) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_KEY_CONFLICT",
                    "profileKey is already in use");
        }
        A2aTrustProfileEntity reloaded = mapper.selectById(entity.getId());
        if (reloaded == null) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_PERSISTENCE_FAILED",
                    "trust profile could not be reloaded after persistence");
        }
        return toDomain(reloaded);
    }

    private A2aTrustProfile toDomain(A2aTrustProfileEntity entity) {
        return new A2aTrustProfile(
                entity.getId(),
                entity.getProfileKey(),
                entity.getName(),
                entity.getDescription(),
                A2aDirection.parse(entity.getDirection()),
                A2aEnvironment.parse(entity.getEnvironment()),
                A2aTrustLevel.parse(entity.getTrustLevel()),
                read(entity.getAuthenticationMethodsJson(), AUTH_METHODS, "authenticationMethodsJson"),
                readNullableSet(entity.getAllowedScopesJson()),
                read(entity.getAuthorizationPolicyJson(), A2aAuthorizationPolicy.class,
                        "authorizationPolicyJson"),
                read(entity.getDataPolicyJson(), A2aDataPolicy.class, "dataPolicyJson"),
                A2aDelegatedIdentityPolicy.parse(entity.getDelegatedIdentityPolicy()),
                A2aPersonalMemoryPolicy.parse(entity.getPersonalMemoryPolicy()),
                entity.getRateLimitPerMinute(),
                entity.getMaxConcurrentTasks(),
                entity.getMaxRequestBytes(),
                entity.getMaxArtifactBytes(),
                entity.getTaskTimeoutMs(),
                Boolean.TRUE.equals(entity.getAllowAnonymous()),
                A2aTrustProfileStatus.parse(entity.getStatus()),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private A2aTrustProfileEntity toEntity(A2aTrustProfile profile) {
        A2aTrustProfileEntity entity = new A2aTrustProfileEntity();
        entity.setId(profile.id());
        entity.setProfileKey(profile.profileKey());
        entity.setName(profile.name());
        entity.setDescription(profile.description());
        entity.setDirection(profile.direction().name());
        entity.setEnvironment(profile.environment().name());
        entity.setTrustLevel(profile.trustLevel().name());
        entity.setAuthenticationMethodsJson(write(profile.authenticationMethods(), "authenticationMethods"));
        entity.setAllowedScopesJson(write(profile.allowedScopes(), "allowedScopes"));
        entity.setAuthorizationPolicyJson(write(profile.authorizationPolicy(), "authorizationPolicy"));
        entity.setDataPolicyJson(write(profile.dataPolicy(), "dataPolicy"));
        entity.setDelegatedIdentityPolicy(profile.delegatedIdentityPolicy().name());
        entity.setPersonalMemoryPolicy(profile.personalMemoryPolicy().name());
        entity.setRateLimitPerMinute(profile.rateLimitPerMinute());
        entity.setMaxConcurrentTasks(profile.maxConcurrentTasks());
        entity.setMaxRequestBytes(profile.maxRequestBytes());
        entity.setMaxArtifactBytes(profile.maxArtifactBytes());
        entity.setTaskTimeoutMs(profile.taskTimeoutMs());
        entity.setAllowAnonymous(profile.allowAnonymous());
        entity.setStatus(profile.status().name());
        entity.setVersion(profile.version());
        entity.setCreatedBy(profile.createdBy());
        entity.setUpdatedBy(profile.updatedBy());
        entity.setCreatedAt(profile.createdAt());
        entity.setUpdatedAt(profile.updatedAt());
        return entity;
    }

    private Set<String> readNullableSet(String json) {
        return json == null || json.isBlank() ? Set.of() : read(json, STRING_SET, "allowedScopesJson");
    }

    private <T> T read(String json, Class<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw corrupted(field, exception);
        }
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw corrupted(field, exception);
        }
    }

    private String write(Object value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new A2aDomainException("A2A_SERIALIZATION_FAILED",
                    field + " could not be serialized");
        }
    }

    private A2aDomainException corrupted(String field, Exception cause) {
        A2aDomainException error = new A2aDomainException("A2A_PERSISTED_POLICY_INVALID",
                "persisted " + field + " is invalid");
        error.initCause(cause);
        return error;
    }
}
