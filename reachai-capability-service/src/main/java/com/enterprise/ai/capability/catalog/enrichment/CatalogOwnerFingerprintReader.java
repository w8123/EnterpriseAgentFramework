package com.enterprise.ai.capability.catalog.enrichment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodRevisionEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodRevisionMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiContractCanonicalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads authoritative accepted fingerprints for graph evidence, without decoding invocation/source contracts. */
@Service
@RequiredArgsConstructor
public class CatalogOwnerFingerprintReader {
    private final BusinessMethodAssetMapper methods;
    private final BusinessMethodRevisionMapper revisions;
    private final HttpApiAssetMapper apis;
    private final HttpApiContractCanonicalizer contracts;

    @Transactional(readOnly = true)
    public Map<String, Fingerprint> read(Long projectId, Collection<Identity> identities) {
        Map<String, Fingerprint> result = new LinkedHashMap<>();
        Set<Long> methodIds = identities.stream().filter(identity -> CatalogSubject.BUSINESS_METHOD.equals(identity.type()))
                .map(Identity::id).collect(Collectors.toSet());
        if (!methodIds.isEmpty()) {
            var owners = methods.selectList(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                    .select(BusinessMethodAssetEntity::getId, BusinessMethodAssetEntity::getProjectId,
                            BusinessMethodAssetEntity::getAcceptedRevisionId, BusinessMethodAssetEntity::getStatus)
                    .eq(BusinessMethodAssetEntity::getProjectId, projectId).in(BusinessMethodAssetEntity::getId, methodIds)
                    .isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId).ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED"));
            var revisionIds = owners.stream().map(BusinessMethodAssetEntity::getAcceptedRevisionId).filter(Objects::nonNull).toList();
            Map<Long, BusinessMethodRevisionEntity> byId = revisionIds.isEmpty() ? Map.of() : revisions.selectList(
                    Wrappers.<BusinessMethodRevisionEntity>lambdaQuery().select(BusinessMethodRevisionEntity::getId,
                            BusinessMethodRevisionEntity::getAssetId, BusinessMethodRevisionEntity::getContractHash)
                            .in(BusinessMethodRevisionEntity::getId, revisionIds)).stream()
                    .collect(Collectors.toMap(BusinessMethodRevisionEntity::getId, value -> value));
            for (var owner : owners) {
                var revision = byId.get(owner.getAcceptedRevisionId());
                if (Objects.equals(projectId, owner.getProjectId()) && revision != null && Objects.equals(owner.getId(), revision.getAssetId())) {
                    result.put(CatalogSubject.BUSINESS_METHOD + ":" + owner.getId(), new Fingerprint(revision.getContractHash(), true));
                }
            }
        }
        Set<Long> apiIds = identities.stream().filter(identity -> CatalogSubject.HTTP_API.equals(identity.type()))
                .map(Identity::id).collect(Collectors.toSet());
        if (!apiIds.isEmpty()) for (var owner : apis.selectList(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .select(HttpApiAssetEntity::getId, HttpApiAssetEntity::getProjectId, HttpApiAssetEntity::getProjectCode,
                        HttpApiAssetEntity::getEnvironment, HttpApiAssetEntity::getIdentityHash, HttpApiAssetEntity::getQualifiedName,
                        HttpApiAssetEntity::getHttpMethod, HttpApiAssetEntity::getRouteTemplate, HttpApiAssetEntity::getMappingConditionsJson,
                        HttpApiAssetEntity::getAcceptedContractHash, HttpApiAssetEntity::getAcceptedContractJson)
                .eq(HttpApiAssetEntity::getProjectId, projectId).in(HttpApiAssetEntity::getId, apiIds)
                .isNotNull(HttpApiAssetEntity::getAcceptedContractHash).isNotNull(HttpApiAssetEntity::getAcceptedContractJson))) {
            if (Objects.equals(projectId, owner.getProjectId()) && contracts.readStoredContract(owner,
                    owner.getAcceptedContractHash(), owner.getAcceptedContractJson()) != null)
                result.put(CatalogSubject.HTTP_API + ":" + owner.getId(), new Fingerprint(owner.getAcceptedContractHash(), true));
        }
        return Map.copyOf(result);
    }

    public record Identity(String type, Long id) { }
    public record Fingerprint(String contractHash, boolean accepted) { }
}
