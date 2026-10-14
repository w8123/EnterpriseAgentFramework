package com.enterprise.ai.capability.catalog.enrichment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete project classification of formal owners, independent of catalog paging and invocation permissions. */
@Service
@RequiredArgsConstructor
public class CatalogClassificationService {
    private final CatalogSubjectResolver subjects;
    private final CapabilityCatalogGroupingService grouping;
    private final CatalogGroupMemberMapper members;

    @Transactional(readOnly = true)
    public ClassificationIndex index(Long projectId, String subjectType) {
        if (projectId == null || projectId <= 0 || subjectType == null
                || !Set.of(CatalogSubject.BUSINESS_METHOD, CatalogSubject.HTTP_API).contains(subjectType)) {
            throw new IllegalArgumentException("请选择当前项目和正式业务能力类型");
        }
        // Classification reads formal owner identities; sources and invocation contracts are outside this query.
        List<CatalogSubject> operations = subjects.operations(projectId, false, subjectType);
        Map<Long, ScanModuleEntity> groups = new LinkedHashMap<>();
        for (ScanModuleEntity group : grouping.list(projectId)) {
            if (group.getId() != null && group.getId() > 0 && Objects.equals(projectId, group.getProjectId())) groups.putIfAbsent(group.getId(), group);
        }
        Map<SubjectKey, Set<Long>> explicit = new LinkedHashMap<>();
        for (CatalogGroupMemberEntity member : members.selectList(Wrappers.<CatalogGroupMemberEntity>lambdaQuery()
                .eq(CatalogGroupMemberEntity::getProjectId, projectId).eq(CatalogGroupMemberEntity::getSubjectType, subjectType)
                .orderByAsc(CatalogGroupMemberEntity::getId))) {
            if (!Objects.equals(projectId, member.getProjectId()) || !groups.containsKey(member.getGroupId())
                    || member.getSubjectId() == null || member.getSubjectId() <= 0) continue;
            explicit.computeIfAbsent(new SubjectKey(member.getSubjectType(), member.getSubjectId()), ignored -> new LinkedHashSet<>()).add(member.getGroupId());
        }
        Map<Long, Set<Long>> membership = new LinkedHashMap<>();
        for (CatalogSubject owner : operations) {
            if (!subjectType.equals(owner.subjectType()) || !Objects.equals(projectId, owner.projectId())
                    || owner.subjectId() == null || owner.subjectId() <= 0) continue;
            Set<Long> ids = membership.computeIfAbsent(owner.subjectId(), ignored -> new LinkedHashSet<>());
            ids.addAll(explicit.getOrDefault(new SubjectKey(subjectType, owner.subjectId()), Set.of()));

        }
        List<Membership> rows = new ArrayList<>();
        Map<Long, Long> counts = new LinkedHashMap<>();
        long ungrouped = 0;
        for (var entry : membership.entrySet()) {
            // Stable group order follows the existing module list, not source discovery order.
            List<Long> ids = groups.keySet().stream().filter(entry.getValue()::contains).toList();
            rows.add(new Membership(entry.getKey(), ids));
            if (ids.isEmpty()) ungrouped++;
            ids.forEach(id -> counts.merge(id, 1L, Long::sum));
        }
        List<Group> classified = groups.values().stream().map(group -> new Group(group.getId(), group.getProjectId(), group.getName(),
                group.getDisplayName(), grouping.parseClasses(group.getSourceClasses()), counts.getOrDefault(group.getId(), 0L))).toList();
        return new ClassificationIndex(projectId, subjectType, classified, List.copyOf(rows), ungrouped);
    }

    /** Read only the formal owner memberships needed for a catalog page, in the same order as the classification index. */
    @Transactional(readOnly = true)
    public Map<Long, List<Label>> labels(Long projectId, String subjectType, List<Long> subjectIds) {
        if (projectId == null || projectId <= 0 || subjectType == null
                || !Set.of(CatalogSubject.BUSINESS_METHOD, CatalogSubject.HTTP_API).contains(subjectType)) {
            throw new IllegalArgumentException("请选择当前项目和正式业务能力类型");
        }
        if (subjectIds == null || subjectIds.isEmpty()) return Map.of();
        List<Long> ids = subjectIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, ScanModuleEntity> groups = new LinkedHashMap<>();
        for (ScanModuleEntity group : grouping.list(projectId)) {
            if (group.getId() != null && group.getId() > 0 && Objects.equals(projectId, group.getProjectId())) {
                groups.putIfAbsent(group.getId(), group);
            }
        }
        Map<Long, Set<Long>> memberships = new LinkedHashMap<>();
        for (CatalogGroupMemberEntity member : members.selectList(Wrappers.<CatalogGroupMemberEntity>lambdaQuery()
                .eq(CatalogGroupMemberEntity::getProjectId, projectId)
                .eq(CatalogGroupMemberEntity::getSubjectType, subjectType)
                .in(CatalogGroupMemberEntity::getSubjectId, ids))) {
            if (Objects.equals(projectId, member.getProjectId()) && subjectType.equals(member.getSubjectType())
                    && ids.contains(member.getSubjectId()) && groups.containsKey(member.getGroupId())) {
                memberships.computeIfAbsent(member.getSubjectId(), ignored -> new LinkedHashSet<>()).add(member.getGroupId());
            }
        }
        Map<Long, List<Label>> result = new LinkedHashMap<>();
        for (Long id : ids) {
            Set<Long> assigned = memberships.getOrDefault(id, Set.of());
            result.put(id, groups.values().stream().filter(group -> assigned.contains(group.getId()))
                    .map(group -> new Label(group.getId(), StringUtils.hasText(group.getDisplayName())
                            ? group.getDisplayName() : group.getName())).toList());
        }
        return result;
    }

    private record SubjectKey(String type, Long id) { }
    public record Label(Long id, String displayName) { }
    public record Group(Long id, Long projectId, String name, String displayName, List<String> sourceClasses, long memberCount) { }
    public record Membership(Long subjectId, List<Long> groupIds) { }
    public record ClassificationIndex(Long projectId, String subjectType, List<Group> groups, List<Membership> memberships, long ungroupedCount) { }
}
