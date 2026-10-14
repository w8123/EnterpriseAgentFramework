package com.enterprise.ai.capability.catalog.enrichment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/** Resolve complete membership before the owning catalog applies filters, count and pagination. */
@Service
@RequiredArgsConstructor
public class CatalogGroupSelection {
    private final CapabilityCatalogGroupingService grouping;
    private final CatalogClassificationService classification;

    public Filter filter(Long projectId, Long groupId, boolean ungrouped) {
        if (ungrouped && groupId != null) throw new IllegalArgumentException("未分类筛选条件无效");
        if (groupId == null && !ungrouped) return null;
        if (projectId == null || projectId <= 0 || (groupId != null && groupId <= 0)) throw new IllegalArgumentException("请选择当前项目的业务分类");
        if (groupId != null) grouping.requireGroup(projectId, groupId);
        return new Filter(projectId, groupId, ungrouped);
    }

    public record Filter(Long projectId, Long groupId, boolean ungrouped) {
        public <T> void apply(LambdaQueryWrapper<T> query, String ownerTable, String type) {
            if (!Set.of("capability_business_method_asset", "capability_http_api_asset").contains(ownerTable)
                    || !Set.of(CatalogSubject.BUSINESS_METHOD, CatalogSubject.HTTP_API).contains(type)) throw new IllegalArgumentException("正式能力过滤身份无效");
            String sql = (ungrouped ? "NOT " : "") + "EXISTS (SELECT 1 FROM capability_catalog_group_member membership "
                    + "JOIN capability_scan_module classification_group ON classification_group.id = membership.group_id "
                    + "AND classification_group.project_id = {0} "
                    + "WHERE membership.project_id = {0} AND membership.subject_type = {1} "
                    + "AND membership.subject_id = " + ownerTable + ".id";
            if (groupId == null) query.apply(sql + ")", projectId, type);
            else query.apply(sql + " AND membership.group_id = {2})", projectId, type, groupId);
        }
    }

    public Map<Long, List<CatalogClassificationService.Label>> labels(Long projectId, String subjectType, List<Long> subjectIds) {
        return classification.labels(projectId, subjectType, subjectIds);
    }
}
