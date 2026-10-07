package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilitySourceStateEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BusinessMethodCatalogService {
    private final BusinessMethodAssetMapper assets;
    private final BusinessMethodAssetStore store;
    private final ScanProjectMapper projects;
    private final CapabilityChangeLifecycle lifecycle;

    public BusinessMethodCatalogSummary summary(Long projectId) {
        return assets.summarize(projectId);
    }

    public IPage<BusinessMethodDefinition> page(int current, int size, String keyword, Boolean enabled, Long projectId) {
        var query = Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                .isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId)
                .ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED");
        if (projectId != null) query.eq(BusinessMethodAssetEntity::getProjectId, projectId);
        if (enabled != null) query.eq(BusinessMethodAssetEntity::getEnabled, enabled);
        if (StringUtils.hasText(keyword)) {
            String term = keyword.trim();
            query.and(q -> q.like(BusinessMethodAssetEntity::getMethodCode, term)
                    .or().like(BusinessMethodAssetEntity::getQualifiedName, term)
                    .or().like(BusinessMethodAssetEntity::getTitle, term)
                    .or().like(BusinessMethodAssetEntity::getDescription, term));
        }
        query.orderByAsc(BusinessMethodAssetEntity::getTitle).orderByAsc(BusinessMethodAssetEntity::getQualifiedName);
        return assets.selectPage(new Page<>(Math.max(1, current), Math.min(100, Math.max(1, size)), true), query)
                .convert(this::definition);
    }

    public Optional<BusinessMethodDefinition> find(String reference) {
        if (!StringUtils.hasText(reference)) return Optional.empty();
        String value = reference.trim();
        return Optional.ofNullable(assets.selectOne(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                .isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId)
                .ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED")
                .and(q -> q.eq(BusinessMethodAssetEntity::getQualifiedName, value)
                        .or().eq(BusinessMethodAssetEntity::getInvocationName, value))))
                .map(this::definition);
    }

    private BusinessMethodDefinition definition(BusinessMethodAssetEntity asset) {
        var revision = store.acceptedRevision(asset);
        var declaration = store.acceptedDeclaration(asset);
        ScanProjectEntity project = projects.selectById(asset.getProjectId());
        CapabilitySourceStateEntity source = lifecycle.sourceState(asset.getQualifiedName());
        String availability;
        if (source == null || project == null) availability = "SOURCE_UNKNOWN";
        else if (!Objects.equals(project.getProjectCode(), asset.getProjectCode())
                || !Objects.equals(source.getProjectId(), asset.getProjectId())
                || !Objects.equals(source.getProjectCode(), asset.getProjectCode())) availability = "CONTRACT_DRIFT";
        else if (source.getSourceContractHash() == null || "REMOVED".equals(asset.getStatus())) availability = "SOURCE_MISSING";
        else if (!Objects.equals(revision.getInvocationHash(), source.getSourceContractHash())
                || !Objects.equals(revision.getInvocationHash(), source.getAcceptedContractHash())
                || !"READY".equals(source.getAvailability())) availability = "CONTRACT_DRIFT";
        else availability = "READY";
        return new BusinessMethodDefinition(asset, revision, declaration, project == null ? null : project.getName(), availability);
    }
}
