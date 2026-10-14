package com.enterprise.ai.capability.catalog.enrichment;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One project transaction validates every owner and revision before creating groups or replacing memberships. */
@Service
@RequiredArgsConstructor
public class CatalogClassificationEditorService {
    private final CatalogSubjectResolver subjects;
    private final CapabilityCatalogGroupingService grouping;
    private final ScanProjectMapper projects;

    @Transactional(readOnly = true)
    public Assignment read(Long projectId, String type, Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("能力身份无效");
        return readBatch(projectId, type, List.of(id)).get(0);
    }

    @Transactional(readOnly = true)
    public List<Assignment> readBatch(Long projectId, String type, List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("请选择 1 至 100 个不重复的能力");
        subjects.requireProject(projectId);
        requireOwners(projectId, type, Set.copyOf(ids));
        return grouping.assignments(projectId, type, ids);
    }

    @Transactional
    public List<Assignment> replace(Long projectId, String type, EditRequest request) {
        if (request == null || request.subjects() == null || request.subjects().isEmpty() || request.subjects().size() > 100
                || request.groupIds() == null || request.newNames() == null || request.groupIds().size() + request.newNames().size() > 30) {
            throw new IllegalArgumentException("请选择 1 至 100 个能力，最多选择 30 个业务分类");
        }
        subjects.requireProject(projectId);
        projects.lockCapabilityChanges(projectId);
        Set<Long> ids = new HashSet<>();
        for (ExpectedSubject item : request.subjects()) {
            if (item == null || item.subjectId() == null || item.subjectId() <= 0 || item.expectedRevision() == null
                    || item.expectedRevision() < 0 || !ids.add(item.subjectId())) throw new IllegalArgumentException("能力身份或分类修订无效");
        }
        requireOwners(projectId, type, ids);
        var before = grouping.assignments(projectId, type, request.subjects().stream().map(ExpectedSubject::subjectId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(Assignment::subjectId, Assignment::revision));
        for (ExpectedSubject item : request.subjects()) {
            if (!java.util.Objects.equals(before.get(item.subjectId()), item.expectedRevision())) {
                throw new IllegalStateException("分类归属已变化，请重读并核对后保存");
            }
        }
        Set<Long> groups = new LinkedHashSet<>();
        for (Long id : request.groupIds()) groups.add(grouping.requireGroup(projectId, id).getId());
        for (String name : request.newNames()) groups.add(grouping.create(projectId, name).getId());
        for (ExpectedSubject item : request.subjects()) grouping.replaceOwner(projectId, type, item.subjectId(), List.copyOf(groups), true);
        return grouping.assignments(projectId, type, request.subjects().stream().map(ExpectedSubject::subjectId).toList());
    }

    private void requireOwners(Long projectId, String type, Set<Long> ids) {
        if (!CatalogSubject.BUSINESS_METHOD.equals(type) && !CatalogSubject.HTTP_API.equals(type)) throw new IllegalArgumentException("能力类型无效");
        Set<Long> found = subjects.ownerIds(projectId, type, ids);
        if (!Set.of(CatalogSubject.BUSINESS_METHOD, CatalogSubject.HTTP_API).contains(type) || !found.containsAll(ids)) {
            throw new IllegalArgumentException("能力不存在或不属于当前项目与类型");
        }
    }

    public record ExpectedSubject(Long subjectId, Long expectedRevision) { }
    public record EditRequest(List<ExpectedSubject> subjects, List<Long> groupIds, List<String> newNames) { }
    public record Assignment(Long projectId, String subjectType, Long subjectId, long revision, List<Long> groupIds) { }
}
