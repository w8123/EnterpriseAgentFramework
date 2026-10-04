package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Transactional source-observation service for the HTTP API domain foundation.
 *
 * <p>It intentionally has no public controller, no accept/apply operation, no Tool projection,
 * and no execution behavior. 3A-2 will adapt concrete discovery sources to this service.</p>
 */
@Service
@RequiredArgsConstructor
public class HttpApiAssetService {

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:base[_-]?url|credentialref|password|token|secret|api[_-]?key)\\s*(?:=|:)|"
                    + "authorization\\s*(?:=|:)\\s*bearer|cookie\\s*(?:=|:)");

    private final HttpApiAssetMapper assetMapper;
    private final HttpApiSourceBindingMapper bindingMapper;
    private final HttpApiContractCanonicalizer canonicalizer;

    /**
     * Uses a current-read transaction: after an asset row lock is acquired, ordinary active
     * binding reads must observe commits that happened while waiting for that lock. MySQL's
     * default REPEATABLE_READ snapshot would not provide that guarantee.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Observation observe(ObserveRequest request) {
        HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical = validate(request);
        String sourceKey = sourceFact(request.sourceKey(), "HTTP API source key", 256, true);
        String sourceLocation = sourceFact(request.sourceLocation(), "HTTP API source location", 1024, false);
        String sourceRevision = sourceFact(request.sourceRevision(), "HTTP API source revision", 256, false);

        AssetResolution assetResolution = ensureAsset(canonical);
        HttpApiSourceBindingEntity sourceSnapshot = findBinding(canonical.scope(), request.sourceKind(), sourceKey);
        // The normal lock order is asset -> source binding. Existing source moves lock the old
        // and new assets in ID order before they acquire the binding row, avoiding cross-move
        // circular waits.
        List<HttpApiAssetEntity> lockedAssets = lockAffectedAssets(
                sourceSnapshot == null ? null : sourceSnapshot.getAssetId(), assetResolution.asset().getId());
        BindingResolution bindingResolution = lockOrCreateBinding(canonical, assetResolution.asset().getId(),
                request.sourceKind(), sourceKey, sourceLocation, sourceRevision, lockedAssets);
        HttpApiSourceBindingEntity binding = bindingResolution.binding();

        if (!bindingResolution.created()) {
            if (bindingChanged(binding, assetResolution.asset().getId(), canonical, sourceLocation, sourceRevision)) {
                applyBindingObservation(binding, assetResolution.asset().getId(), canonical, sourceLocation, sourceRevision);
            } else {
                touchBinding(binding);
            }
            bindingMapper.updateById(binding);
        }

        HttpApiAssetEntity aggregate = recomputeAffectedAssets(lockedAssets, assetResolution.asset().getId());
        return new Observation(aggregate, bindingMapper.selectById(binding.getId()), assetResolution.created());
    }

    /**
     * Validates all non-database source facts before a full inventory begins mutating bindings.
     * Registry adapters use this to keep one malformed operation from partially reconciling the
     * rest of a snapshot.
     */
    public HttpApiContractCanonicalizer.CanonicalHttpApiContract validate(ObserveRequest request) {
        if (request == null || request.scope() == null || request.sourceKind() == null || request.contract() == null) {
            throw new IllegalArgumentException("HTTP API observation is incomplete");
        }
        sourceFact(request.sourceKey(), "HTTP API source key", 256, true);
        sourceFact(request.sourceLocation(), "HTTP API source location", 1024, false);
        sourceFact(request.sourceRevision(), "HTTP API source revision", 256, false);
        return canonicalizer.canonicalize(request.scope(), request.contract());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Removal remove(RemoveRequest request) {
        if (request == null || request.scope() == null || request.sourceKind() == null) {
            throw new IllegalArgumentException("HTTP API source removal is incomplete");
        }
        String sourceKey = sourceFact(request.sourceKey(), "HTTP API source key", 256, true);
        HttpApiSourceBindingEntity sourceSnapshot = findBinding(request.scope(), request.sourceKind(), sourceKey);
        if (sourceSnapshot == null) {
            return new Removal(null, false);
        }
        List<HttpApiAssetEntity> lockedAssets = lockAffectedAssets(sourceSnapshot.getAssetId());
        HttpApiSourceBindingEntity binding = lockExistingBinding(request.scope(), request.sourceKind(), sourceKey,
                lockedAssets);
        if (binding == null) {
            return new Removal(null, false);
        }
        if (HttpApiSourceBindingStatus.REMOVED.name().equals(binding.getStatus())) {
            return new Removal(recomputeAffectedAssets(lockedAssets, binding.getAssetId()), false);
        }
        binding.setStatus(HttpApiSourceBindingStatus.REMOVED.name());
        binding.setRemovedAt(now());
        binding.setUpdatedAt(now());
        bindingMapper.updateById(binding);
        return new Removal(recomputeAffectedAssets(lockedAssets, binding.getAssetId()), true);
    }

    private AssetResolution ensureAsset(HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical) {
        HttpApiAssetEntity existing = findAsset(canonical.scope(), canonical.identityHash());
        if (existing != null) {
            return new AssetResolution(existing, false);
        }
        HttpApiAssetEntity created = newAsset(canonical);
        try {
            assetMapper.insert(created);
            return new AssetResolution(created, true);
        } catch (DuplicateKeyException duplicate) {
            existing = findAsset(canonical.scope(), canonical.identityHash());
            if (existing == null) {
                throw duplicate;
            }
            return new AssetResolution(existing, false);
        }
    }

    private BindingResolution lockOrCreateBinding(HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical,
                                                  Long assetId,
                                                  HttpApiSourceKind sourceKind,
                                                  String sourceKey,
                                                  String sourceLocation,
                                                  String sourceRevision,
                                                  List<HttpApiAssetEntity> lockedAssets) {
        HttpApiSourceBindingEntity existing = lockExistingBinding(canonical.scope(), sourceKind, sourceKey,
                lockedAssets);
        if (existing != null) {
            return new BindingResolution(existing, false);
        }

        HttpApiSourceBindingEntity created = newBinding(canonical, assetId, sourceKind, sourceKey,
                sourceLocation, sourceRevision);
        try {
            bindingMapper.insert(created);
            return new BindingResolution(created, true);
        } catch (DuplicateKeyException duplicate) {
            existing = lockExistingBinding(canonical.scope(), sourceKind, sourceKey, lockedAssets);
            if (existing == null) {
                throw duplicate;
            }
            return new BindingResolution(existing, false);
        }
    }

    private List<HttpApiAssetEntity> lockAffectedAssets(Long... ids) {
        Set<Long> uniqueIds = new java.util.TreeSet<>();
        for (Long id : ids) {
            if (id != null) {
                uniqueIds.add(id);
            }
        }
        List<HttpApiAssetEntity> locked = new ArrayList<>();
        for (Long id : uniqueIds) {
            locked.add(lockAsset(id));
        }
        return locked;
    }

    /** Called only before a source-binding row lock; it never reverses the normal lock order. */
    private List<HttpApiAssetEntity> lockAdditionalAssets(List<HttpApiAssetEntity> locked,
                                                           Long... ids) {
        Set<Long> heldIds = new java.util.TreeSet<>();
        for (HttpApiAssetEntity asset : locked) {
            heldIds.add(asset.getId());
        }
        Set<Long> missingIds = new java.util.TreeSet<>();
        for (Long id : ids) {
            if (id != null && !heldIds.contains(id)) {
                missingIds.add(id);
            }
        }
        for (Long id : missingIds) {
            locked.add(lockAsset(id));
        }
        locked.sort(java.util.Comparator.comparing(HttpApiAssetEntity::getId));
        return locked;
    }

    private HttpApiSourceBindingEntity lockExistingBinding(HttpApiServiceScope scope,
                                                           HttpApiSourceKind sourceKind,
                                                           String sourceKey,
                                                           List<HttpApiAssetEntity> lockedAssets) {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpApiSourceBindingEntity snapshot = findBinding(scope, sourceKind, sourceKey);
            if (snapshot == null) {
                return null;
            }
            lockAdditionalAssets(lockedAssets, snapshot.getAssetId());

            HttpApiSourceBindingEntity current = findBinding(scope, sourceKind, sourceKey);
            if (current == null || !Objects.equals(snapshot.getAssetId(), current.getAssetId())) {
                continue;
            }
            HttpApiSourceBindingEntity locked = bindingMapper.selectByScopeAndSourceForUpdate(
                    scope.projectId(), scope.projectCode(), scope.environment(), sourceKind.name(), sourceKey);
            if (locked == null) {
                continue;
            }
            if (!hasLockedAsset(lockedAssets, locked.getAssetId())) {
                throw new IllegalStateException("HTTP API source binding changed outside the asset lock protocol");
            }
            return locked;
        }
        throw new IllegalStateException("HTTP API source binding did not stabilize for locking");
    }

    private boolean hasLockedAsset(List<HttpApiAssetEntity> lockedAssets, Long assetId) {
        return lockedAssets.stream().anyMatch(asset -> asset.getId().equals(assetId));
    }

    private HttpApiAssetEntity recomputeAffectedAssets(List<HttpApiAssetEntity> lockedAssets,
                                                        Long targetAssetId) {
        HttpApiAssetEntity aggregate = null;
        for (HttpApiAssetEntity lockedAsset : lockedAssets) {
            HttpApiAssetEntity recomputed = recomputeLocked(lockedAsset);
            if (lockedAsset.getId().equals(targetAssetId)) {
                aggregate = recomputed;
            }
        }
        if (aggregate == null) {
            throw new IllegalStateException("HTTP API target asset was not locked");
        }
        return aggregate;
    }

    private HttpApiAssetEntity lockAsset(Long assetId) {
        HttpApiAssetEntity asset = assetMapper.selectByIdForUpdate(assetId);
        if (asset == null) {
            throw new IllegalStateException("HTTP API asset is missing: " + assetId);
        }
        return asset;
    }

    private HttpApiAssetEntity newAsset(HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical) {
        HttpApiAssetEntity asset = new HttpApiAssetEntity();
        asset.setProjectId(canonical.scope().projectId());
        asset.setProjectCode(canonical.scope().projectCode());
        asset.setEnvironment(canonical.scope().environment());
        asset.setIdentityHash(canonical.identityHash());
        asset.setQualifiedName(canonical.qualifiedName());
        asset.setHttpMethod(canonical.httpMethod());
        asset.setRouteTemplate(canonical.routeTemplate());
        asset.setMappingConditionsJson(canonical.mappingConditionsJson());
        asset.setStatus(HttpApiAssetStatus.DISCOVERED.name());
        asset.setCreatedAt(now());
        asset.setUpdatedAt(now());
        return asset;
    }

    private HttpApiSourceBindingEntity newBinding(HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical,
                                                  Long assetId,
                                                  HttpApiSourceKind sourceKind,
                                                  String sourceKey,
                                                  String sourceLocation,
                                                  String sourceRevision) {
        HttpApiSourceBindingEntity binding = new HttpApiSourceBindingEntity();
        binding.setAssetId(assetId);
        binding.setProjectId(canonical.scope().projectId());
        binding.setProjectCode(canonical.scope().projectCode());
        binding.setEnvironment(canonical.scope().environment());
        binding.setSourceKind(sourceKind.name());
        binding.setSourceKey(sourceKey);
        applyBindingObservation(binding, assetId, canonical, sourceLocation, sourceRevision);
        binding.setCreatedAt(now());
        return binding;
    }

    private void applyBindingObservation(HttpApiSourceBindingEntity binding,
                                         Long assetId,
                                         HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical,
                                         String sourceLocation,
                                         String sourceRevision) {
        binding.setAssetId(assetId);
        binding.setSourceLocation(sourceLocation);
        binding.setSourceRevision(sourceRevision);
        binding.setSourceContractHash(canonical.contractHash());
        binding.setSourceContractJson(canonical.contractJson());
        binding.setStatus(HttpApiSourceBindingStatus.DISCOVERED.name());
        binding.setObservedAt(now());
        binding.setRemovedAt(null);
        binding.setUpdatedAt(now());
    }

    private void touchBinding(HttpApiSourceBindingEntity binding) {
        LocalDateTime observedAt = now();
        binding.setObservedAt(observedAt);
        binding.setUpdatedAt(observedAt);
    }

    private boolean bindingChanged(HttpApiSourceBindingEntity binding,
                                   Long assetId,
                                   HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical,
                                   String sourceLocation,
                                   String sourceRevision) {
        return !Objects.equals(binding.getAssetId(), assetId)
                || !Objects.equals(binding.getSourceLocation(), sourceLocation)
                || !Objects.equals(binding.getSourceRevision(), sourceRevision)
                || !Objects.equals(binding.getSourceContractHash(), canonical.contractHash())
                || !Objects.equals(binding.getSourceContractJson(), canonical.contractJson())
                || HttpApiSourceBindingStatus.REMOVED.name().equals(binding.getStatus());
    }

    /** Caller holds this asset's database row lock, serializing all aggregate transitions. */
    private HttpApiAssetEntity recomputeLocked(HttpApiAssetEntity asset) {
        Long assetId = asset.getId();
        List<HttpApiSourceBindingEntity> active = bindingMapper.selectList(
                Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getAssetId, assetId)
                        .ne(HttpApiSourceBindingEntity::getStatus, HttpApiSourceBindingStatus.REMOVED.name())
                        .orderByAsc(HttpApiSourceBindingEntity::getId));

        HttpApiAssetStatus aggregateStatus;
        HttpApiSourceBindingStatus bindingStatus = null;
        if (active.isEmpty()) {
            aggregateStatus = HttpApiAssetStatus.SOURCE_MISSING;
        } else {
            Set<String> hashes = new LinkedHashSet<>();
            active.forEach(binding -> hashes.add(binding.getSourceContractHash()));
            if (hashes.size() > 1) {
                aggregateStatus = HttpApiAssetStatus.CONFLICT;
                bindingStatus = HttpApiSourceBindingStatus.CONFLICT;
            } else if (asset.getAcceptedContractHash() != null && !asset.getAcceptedContractHash().isBlank()) {
                aggregateStatus = asset.getAcceptedContractHash().equals(active.get(0).getSourceContractHash())
                        ? HttpApiAssetStatus.ACCEPTED : HttpApiAssetStatus.CONTRACT_DRIFT;
                bindingStatus = active.size() > 1
                        ? HttpApiSourceBindingStatus.EQUIVALENT : HttpApiSourceBindingStatus.DISCOVERED;
            } else {
                aggregateStatus = HttpApiAssetStatus.DISCOVERED;
                bindingStatus = active.size() > 1
                        ? HttpApiSourceBindingStatus.EQUIVALENT : HttpApiSourceBindingStatus.DISCOVERED;
            }
        }

        if (bindingStatus != null) {
            for (HttpApiSourceBindingEntity binding : active) {
                if (!bindingStatus.name().equals(binding.getStatus())) {
                    binding.setStatus(bindingStatus.name());
                    binding.setUpdatedAt(now());
                    bindingMapper.updateById(binding);
                }
            }
        }
        if (!aggregateStatus.name().equals(asset.getStatus())) {
            asset.setStatus(aggregateStatus.name());
            asset.setUpdatedAt(now());
            assetMapper.updateById(asset);
        }
        return assetMapper.selectById(assetId);
    }

    private HttpApiAssetEntity findAsset(HttpApiServiceScope scope, String identityHash) {
        return assetMapper.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, scope.projectId())
                .eq(HttpApiAssetEntity::getProjectCode, scope.projectCode())
                .eq(HttpApiAssetEntity::getEnvironment, scope.environment())
                .eq(HttpApiAssetEntity::getIdentityHash, identityHash)
                .last("LIMIT 1"));
    }

    private HttpApiSourceBindingEntity findBinding(HttpApiServiceScope scope,
                                                   HttpApiSourceKind sourceKind,
                                                   String sourceKey) {
        return bindingMapper.selectOne(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getProjectId, scope.projectId())
                .eq(HttpApiSourceBindingEntity::getProjectCode, scope.projectCode())
                .eq(HttpApiSourceBindingEntity::getEnvironment, scope.environment())
                .eq(HttpApiSourceBindingEntity::getSourceKind, sourceKind.name())
                .eq(HttpApiSourceBindingEntity::getSourceKey, sourceKey)
                .last("LIMIT 1"));
    }

    private String sourceFact(String raw, String field, int maxLength, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return null;
        }
        String value = raw.trim();
        if (value.length() > maxLength || SECRET_ASSIGNMENT.matcher(value).find()) {
            throw new IllegalArgumentException(field + " cannot contain secret material");
        }
        return value;
    }

    private LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    public record ObserveRequest(
            HttpApiServiceScope scope,
            HttpApiSourceKind sourceKind,
            String sourceKey,
            String sourceLocation,
            String sourceRevision,
            HttpApiOperationContract contract
    ) {
    }

    public record RemoveRequest(
            HttpApiServiceScope scope,
            HttpApiSourceKind sourceKind,
            String sourceKey
    ) {
    }

    public record Observation(
            HttpApiAssetEntity asset,
            HttpApiSourceBindingEntity binding,
            boolean assetCreated
    ) {
    }

    public record Removal(HttpApiAssetEntity asset, boolean changed) {
    }

    private record AssetResolution(HttpApiAssetEntity asset, boolean created) {
    }

    private record BindingResolution(HttpApiSourceBindingEntity binding, boolean created) {
    }
}
