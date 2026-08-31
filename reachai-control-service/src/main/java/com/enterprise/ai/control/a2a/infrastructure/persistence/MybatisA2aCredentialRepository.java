package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialStatus;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MybatisA2aCredentialRepository implements A2aCredentialRepository {

    private final A2aCredentialMapper mapper;

    @Override
    public Optional<A2aCredential> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<A2aCredential> findUsableByKey(String credentialKey, LocalDateTime now) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<A2aCredentialEntity>lambdaQuery()
                        .eq(A2aCredentialEntity::getCredentialKey, credentialKey)
                        .eq(A2aCredentialEntity::getDirection, A2aDirection.INBOUND.name())
                        .in(A2aCredentialEntity::getStatus, "ACTIVE", "GRACE")
                        .and(q -> q.isNull(A2aCredentialEntity::getNotBefore)
                                .or().le(A2aCredentialEntity::getNotBefore, now))
                        .and(q -> q.isNull(A2aCredentialEntity::getExpiresAt)
                                .or().gt(A2aCredentialEntity::getExpiresAt, now))
                        .orderByDesc(A2aCredentialEntity::getVersionNo)
                        .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public Optional<A2aCredential> findUsableByKeyAndVersion(
            String credentialKey, int versionNo, LocalDateTime now) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<A2aCredentialEntity>lambdaQuery()
                        .eq(A2aCredentialEntity::getCredentialKey, credentialKey)
                        .eq(A2aCredentialEntity::getDirection, A2aDirection.INBOUND.name())
                        .eq(A2aCredentialEntity::getVersionNo, versionNo)
                        .in(A2aCredentialEntity::getStatus, "ACTIVE", "GRACE")
                        .and(q -> q.isNull(A2aCredentialEntity::getNotBefore)
                                .or().le(A2aCredentialEntity::getNotBefore, now))
                        .and(q -> q.isNull(A2aCredentialEntity::getExpiresAt)
                                .or().gt(A2aCredentialEntity::getExpiresAt, now))
                        .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public boolean existsByKey(String credentialKey) {
        return mapper.selectCount(Wrappers.<A2aCredentialEntity>lambdaQuery()
                .eq(A2aCredentialEntity::getCredentialKey, credentialKey)) > 0;
    }

    @Override
    public int nextVersion(String credentialKey) {
        A2aCredentialEntity latest = mapper.selectOne(Wrappers.<A2aCredentialEntity>lambdaQuery()
                .select(A2aCredentialEntity::getVersionNo)
                .eq(A2aCredentialEntity::getCredentialKey, credentialKey)
                .orderByDesc(A2aCredentialEntity::getVersionNo)
                .last("LIMIT 1"));
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    @Override
    public Page findPage(String search, String status, int limit, int offset) {
        var query = Wrappers.<A2aCredentialEntity>lambdaQuery()
                .eq(status != null, A2aCredentialEntity::getStatus, status)
                .and(search != null && !search.isBlank(), nested -> nested
                        .like(A2aCredentialEntity::getCredentialKey, search.trim())
                        .or().like(A2aCredentialEntity::getName, search.trim())
                        .or().like(A2aCredentialEntity::getFingerprint, search.trim()))
                .orderByDesc(A2aCredentialEntity::getUpdatedAt)
                .orderByDesc(A2aCredentialEntity::getId);
        long total = mapper.selectCount(query);
        query.last("LIMIT " + limit + " OFFSET " + offset);
        return new Page(mapper.selectList(query).stream().map(this::toDomain).toList(), total);
    }

    @Override
    public A2aCredential save(A2aCredential credential) {
        A2aCredentialEntity entity = toEntity(credential);
        try {
            int affected = entity.getId() == null ? mapper.insert(entity) : mapper.updateById(entity);
            if (affected != 1) {
                throw new A2aDomainException("A2A_CREDENTIAL_CONFLICT",
                        "credential changed concurrently");
            }
        } catch (DuplicateKeyException exception) {
            throw new A2aDomainException("A2A_CREDENTIAL_VERSION_CONFLICT",
                    "credential version already exists");
        }
        A2aCredentialEntity reloaded = mapper.selectById(entity.getId());
        if (reloaded == null) {
            throw new A2aDomainException("A2A_CREDENTIAL_PERSISTENCE_FAILED",
                    "credential could not be reloaded after persistence");
        }
        return toDomain(reloaded);
    }

    @Override
    public void markUsed(long credentialId, LocalDateTime usedAt) {
        mapper.update(null, Wrappers.<A2aCredentialEntity>lambdaUpdate()
                .eq(A2aCredentialEntity::getId, credentialId)
                .set(A2aCredentialEntity::getLastUsedAt, usedAt));
    }

    private A2aCredential toDomain(A2aCredentialEntity entity) {
        return new A2aCredential(
                entity.getId(), entity.getCredentialKey(), entity.getName(),
                A2aDirection.parse(entity.getDirection()),
                A2aCredentialType.parse(entity.getCredentialType()), entity.getMaterialMode(),
                entity.getMaterialHash(), entity.getMaterialCiphertext(), entity.getEncryptionKeyId(),
                entity.getEncryptionNonce(), entity.getExternalRef(), entity.getFingerprint(),
                entity.getVersionNo(),
                A2aCredentialStatus.parse(entity.getStatus()), entity.getNotBefore(),
                entity.getExpiresAt(), entity.getLastUsedAt(), entity.getRotatedFromId(),
                entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private A2aCredentialEntity toEntity(A2aCredential credential) {
        A2aCredentialEntity entity = new A2aCredentialEntity();
        entity.setId(credential.id());
        entity.setCredentialKey(credential.credentialKey());
        entity.setName(credential.name());
        entity.setDirection(credential.direction().name());
        entity.setCredentialType(credential.credentialType().name());
        entity.setMaterialMode(credential.materialMode());
        entity.setMaterialHash(credential.materialHash());
        entity.setMaterialSalt(null);
        entity.setMaterialCiphertext(credential.materialCiphertext());
        entity.setEncryptionKeyId(credential.encryptionKeyId());
        entity.setEncryptionNonce(credential.encryptionNonce());
        entity.setExternalRef(credential.externalRef());
        entity.setFingerprint(credential.fingerprint());
        entity.setVersionNo(credential.versionNo());
        entity.setStatus(credential.status().name());
        entity.setNotBefore(credential.notBefore());
        entity.setExpiresAt(credential.expiresAt());
        entity.setLastUsedAt(credential.lastUsedAt());
        entity.setRotatedFromId(credential.rotatedFromId());
        entity.setCreatedBy(credential.createdBy());
        entity.setCreatedAt(credential.createdAt());
        entity.setUpdatedAt(credential.updatedAt());
        return entity;
    }
}
