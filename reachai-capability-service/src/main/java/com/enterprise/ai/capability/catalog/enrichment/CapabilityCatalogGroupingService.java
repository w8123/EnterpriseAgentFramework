package com.enterprise.ai.capability.catalog.enrichment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.capability.catalog.CapabilitySourceOwnership;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.capability.catalog.graph.CapabilityApiGraphGroupingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Owns catalog grouping only; source schemas and invocation definitions are never edited here. */
@Service
@RequiredArgsConstructor
public class CapabilityCatalogGroupingService {
    private final ScanModuleMapper modules;
    private final ScanProjectMapper projects;
    private final ScanProjectToolMapper observations;
    private final CatalogGroupMemberMapper members;
    private final CatalogClassificationStateMapper states;
    private final CapabilityApiGraphGroupingService graph;
    private final ObjectMapper json;

    public List<ScanModuleEntity> list(Long projectId) {
        return modules.selectList(Wrappers.<ScanModuleEntity>lambdaQuery()
                .eq(ScanModuleEntity::getProjectId, projectId).orderByAsc(ScanModuleEntity::getName));
    }

    /** Persisted formal members of one classification; source observations do not define owner membership. */
    public OwnerMemberIds formalMemberIds(Long projectId, Long groupId) {
        var methods = new LinkedHashSet<Long>();
        var apis = new LinkedHashSet<Long>();
        for (CatalogGroupMemberEntity member : members.selectList(Wrappers.<CatalogGroupMemberEntity>lambdaQuery()
                .eq(CatalogGroupMemberEntity::getProjectId, projectId)
                .eq(CatalogGroupMemberEntity::getGroupId, groupId)
                .in(CatalogGroupMemberEntity::getSubjectType, CatalogSubject.BUSINESS_METHOD, CatalogSubject.HTTP_API)
                .orderByAsc(CatalogGroupMemberEntity::getId))) {
            if (!Objects.equals(projectId, member.getProjectId()) || !Objects.equals(groupId, member.getGroupId())
                    || member.getSubjectId() == null || member.getSubjectId() <= 0) continue;
            if (CatalogSubject.BUSINESS_METHOD.equals(member.getSubjectType())) methods.add(member.getSubjectId());
            else if (CatalogSubject.HTTP_API.equals(member.getSubjectType())) apis.add(member.getSubjectId());
        }
        return new OwnerMemberIds(List.copyOf(methods), List.copyOf(apis));
    }

    public record OwnerMemberIds(List<Long> methodIds, List<Long> apiIds) { }

    @Transactional
    public void deleteProject(Long projectId) {
        members.delete(Wrappers.<CatalogGroupMemberEntity>lambdaQuery().eq(CatalogGroupMemberEntity::getProjectId, projectId));
        states.delete(Wrappers.<CatalogClassificationStateEntity>lambdaQuery().eq(CatalogClassificationStateEntity::getProjectId, projectId));
    }

    @Transactional
    public void bootstrap(Long projectId, List<ScanProjectToolEntity> tools) {
        projects.lockCapabilityChanges(projectId);
        Map<String, ScanModuleEntity> bySource = new LinkedHashMap<>();
        for (ScanModuleEntity group : list(projectId)) {
            bySource.put(normalize(group.getName()), group);
            bySource.putIfAbsent(normalize(group.getDisplayName()), group);
            for (String source : parseClasses(group.getSourceClasses())) bySource.put(normalize(source), group);
        }
        for (ScanProjectToolEntity tool : tools == null ? List.<ScanProjectToolEntity>of() : tools) {
            if (!Objects.equals(projectId, tool.getProjectId())) throw new IllegalArgumentException("来源不属于当前项目");
            String sourceName = sourceGroupName(tool);
            ScanModuleEntity group = bySource.get(normalize(sourceName));
            if (group == null) {
                group = new ScanModuleEntity();
                group.setProjectId(projectId);
                group.setName(sourceName);
                group.setDisplayName(sourceName);
                group.setSourceClasses(writeClasses(List.of(sourceName)));
                modules.insert(group);
                bySource.put(normalize(sourceName), group);
            }
            if (!Objects.equals(tool.getModuleId(), group.getId())) {
                tool.setModuleId(group.getId());
                observations.updateById(tool);
            }
        }
    }

    public List<ScanModuleEntity> groups(CatalogSubject subject) {
        if (CatalogSubject.BUSINESS_METHOD.equals(subject.subjectType()) || CatalogSubject.HTTP_API.equals(subject.subjectType())) {
            var ids = ownerGroupIds(subject.projectId(), subject.subjectType(), subject.subjectId());
            return list(subject.projectId()).stream().filter(group -> ids.contains(group.getId())).toList();
        }
        Map<Long, ScanModuleEntity> byId = new LinkedHashMap<>();
        var membershipQuery = Wrappers.<CatalogGroupMemberEntity>lambdaQuery().eq(CatalogGroupMemberEntity::getProjectId, subject.projectId())
                .and(query -> {
                    query.eq(CatalogGroupMemberEntity::getSubjectType, subject.subjectType()).eq(CatalogGroupMemberEntity::getSubjectId, subject.subjectId());
                    for (ScanProjectToolEntity observation : subject.observations()) query.or(nested -> nested
                            .eq(CatalogGroupMemberEntity::getSubjectType, CatalogSubject.SOURCE_OBSERVATION).eq(CatalogGroupMemberEntity::getSubjectId, observation.getId()));
                });
        for (CatalogGroupMemberEntity member : members.selectList(membershipQuery)) {
            ScanModuleEntity group = modules.selectById(member.getGroupId());
            if (group != null && Objects.equals(subject.projectId(), group.getProjectId())) byId.put(group.getId(), group);
        }
        for (ScanProjectToolEntity observation : subject.observations()) {
            if (observation.getModuleId() == null) continue;
            ScanModuleEntity group = modules.selectById(observation.getModuleId());
            if (group != null && Objects.equals(subject.projectId(), group.getProjectId())) byId.put(group.getId(), group);
        }
        return List.copyOf(byId.values());
    }

    /** Promote only adjunct references when a unique owner is known; no asset or source contract is created. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void adoptInLockedProject(CatalogSubject subject) {
        if (!subject.operation() || CatalogSubject.SOURCE_OBSERVATION.equals(subject.subjectType()) || subject.observations().isEmpty()) return;
        var state = state(subject.projectId(), subject.subjectType(), subject.subjectId());
        if (state != null && Boolean.TRUE.equals(state.getManual())) return;
        java.util.Set<Long> inherited = new java.util.LinkedHashSet<>(ownerGroupIds(subject.projectId(), subject.subjectType(), subject.subjectId()));
        if (state == null) for (var observation : subject.observations()) if (observation.getModuleId() != null) inherited.add(observation.getModuleId());
        boolean promoted = false;
        for (CatalogGroupMemberEntity candidate : members.selectList(Wrappers.<CatalogGroupMemberEntity>lambdaQuery()
                .eq(CatalogGroupMemberEntity::getProjectId, subject.projectId()).eq(CatalogGroupMemberEntity::getSubjectType, CatalogSubject.SOURCE_OBSERVATION)
                .in(CatalogGroupMemberEntity::getSubjectId, subject.observations().stream().map(ScanProjectToolEntity::getId).toList()))) {
            long duplicate = members.selectCount(Wrappers.<CatalogGroupMemberEntity>lambdaQuery().eq(CatalogGroupMemberEntity::getProjectId, subject.projectId())
                    .eq(CatalogGroupMemberEntity::getGroupId, candidate.getGroupId()).eq(CatalogGroupMemberEntity::getSubjectType, subject.subjectType()).eq(CatalogGroupMemberEntity::getSubjectId, subject.subjectId()));
            inherited.add(candidate.getGroupId());
            promoted = true;
            if (duplicate > 0) members.deleteById(candidate.getId());
            else { candidate.setSubjectType(subject.subjectType()); candidate.setSubjectId(subject.subjectId()); members.updateById(candidate); }
        }
        if (!inherited.isEmpty() && (state == null || promoted)) replaceOwner(subject.projectId(), subject.subjectType(), subject.subjectId(), List.copyOf(inherited), promoted);
    }

    @Transactional
    public void assign(CatalogSubject subject, Long groupId) {
        projects.lockCapabilityChanges(subject.projectId());
        requireGroup(subject.projectId(), groupId);
        if (!subject.operation()) throw new IllegalArgumentException("只有业务操作可以加入分组");
        if (!CatalogSubject.SOURCE_OBSERVATION.equals(subject.subjectType())) {
            var ids = new java.util.LinkedHashSet<>(ownerGroupIds(subject.projectId(), subject.subjectType(), subject.subjectId()));
            ids.add(groupId); replaceOwner(subject.projectId(), subject.subjectType(), subject.subjectId(), List.copyOf(ids), true); return;
        }
        if (members.selectCount(Wrappers.<CatalogGroupMemberEntity>lambdaQuery()
                .eq(CatalogGroupMemberEntity::getProjectId, subject.projectId())
                .eq(CatalogGroupMemberEntity::getGroupId, groupId)
                .eq(CatalogGroupMemberEntity::getSubjectType, subject.subjectType())
                .eq(CatalogGroupMemberEntity::getSubjectId, subject.subjectId())) == 0) {
            CatalogGroupMemberEntity member = new CatalogGroupMemberEntity();
            member.setProjectId(subject.projectId()); member.setGroupId(groupId);
            member.setSubjectType(subject.subjectType()); member.setSubjectId(subject.subjectId());
            members.insert(member);
        }
    }

    @Transactional
    public ScanModuleEntity rename(Long projectId, Long groupId, String displayName) {
        projects.lockCapabilityChanges(projectId);
        ScanModuleEntity group = requireGroup(projectId, groupId);
        if (!StringUtils.hasText(displayName) || displayName.trim().length() > 256) {
            throw new IllegalArgumentException("分组显示名不能为空且不能超过256字符");
        }
        group.setDisplayName(displayName.trim()); modules.updateById(group); graph.rename(projectId, groupId, group.getDisplayName()); return group;
    }

    public void lockProject(Long projectId) { projects.lockCapabilityChanges(projectId); }

    public long revision(Long projectId, String type, Long id) {
        var state = state(projectId, type, id); return state == null ? 0L : state.getRevision();
    }

    public List<CatalogClassificationEditorService.Assignment> assignments(Long projectId, String type, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var revisions = states.selectList(Wrappers.<CatalogClassificationStateEntity>lambdaQuery()
                .eq(CatalogClassificationStateEntity::getProjectId, projectId).eq(CatalogClassificationStateEntity::getSubjectType, type)
                .in(CatalogClassificationStateEntity::getSubjectId, ids)).stream().collect(java.util.stream.Collectors.toMap(
                        CatalogClassificationStateEntity::getSubjectId, row -> row.getRevision() == null ? 0L : row.getRevision()));
        java.util.Map<Long, List<Long>> groups = members.selectList(Wrappers.<CatalogGroupMemberEntity>lambdaQuery()
                .eq(CatalogGroupMemberEntity::getProjectId, projectId).eq(CatalogGroupMemberEntity::getSubjectType, type)
                .in(CatalogGroupMemberEntity::getSubjectId, ids).orderByAsc(CatalogGroupMemberEntity::getGroupId))
                .stream().collect(java.util.stream.Collectors.groupingBy(CatalogGroupMemberEntity::getSubjectId,
                        java.util.stream.Collectors.mapping(CatalogGroupMemberEntity::getGroupId, java.util.stream.Collectors.toList())));
        return ids.stream().map(id -> new CatalogClassificationEditorService.Assignment(projectId, type, id,
                revisions.getOrDefault(id, 0L), groups.getOrDefault(id, List.of()).stream().distinct().toList())).toList();
    }

    private CatalogClassificationStateEntity state(Long projectId, String type, Long id) {
        return states.selectOne(Wrappers.<CatalogClassificationStateEntity>lambdaQuery()
                .eq(CatalogClassificationStateEntity::getProjectId, projectId)
                .eq(CatalogClassificationStateEntity::getSubjectType, type).eq(CatalogClassificationStateEntity::getSubjectId, id));
    }

    public List<Long> ownerGroupIds(Long projectId, String type, Long id) {
        return members.selectList(Wrappers.<CatalogGroupMemberEntity>lambdaQuery()
                .eq(CatalogGroupMemberEntity::getProjectId, projectId).eq(CatalogGroupMemberEntity::getSubjectType, type)
                .eq(CatalogGroupMemberEntity::getSubjectId, id).orderByAsc(CatalogGroupMemberEntity::getGroupId))
                .stream().map(CatalogGroupMemberEntity::getGroupId).distinct().toList();
    }

    /** The caller holds the project lock; even an empty manual set is an authoritative decision. */
    public void replaceOwner(Long projectId, String type, Long id, List<Long> groupIds, boolean manual) {
        var state = state(projectId, type, id);
        if (!manual && state != null && Boolean.TRUE.equals(state.getManual())) return;
        List<Long> normalized = groupIds.stream().distinct().sorted().toList();
        normalized.forEach(group -> requireGroup(projectId, group));
        if (state != null && Objects.equals(state.getManual(), manual) && ownerGroupIds(projectId, type, id).equals(normalized)) return;
        members.delete(Wrappers.<CatalogGroupMemberEntity>lambdaQuery().eq(CatalogGroupMemberEntity::getProjectId, projectId)
                .eq(CatalogGroupMemberEntity::getSubjectType, type).eq(CatalogGroupMemberEntity::getSubjectId, id));
        for (Long group : normalized) {
            CatalogGroupMemberEntity member = new CatalogGroupMemberEntity();
            member.setProjectId(projectId); member.setSubjectType(type); member.setSubjectId(id); member.setGroupId(group); members.insert(member);
        }
        boolean inserting = state == null;
        if (inserting) { state = new CatalogClassificationStateEntity(); state.setProjectId(projectId); state.setSubjectType(type); state.setSubjectId(id); state.setRevision(0L); }
        state.setManual(manual); state.setRevision(state.getRevision() + 1);
        if (inserting) states.insert(state); else states.updateById(state);
    }

    /** Initial classification persists on the owner; missing or renamed sources cannot erase it. */
    public void seedOwner(Long projectId, String type, Long id, List<Long> groupIds) {
        if (state(projectId, type, id) != null || !ownerGroupIds(projectId, type, id).isEmpty() || groupIds.isEmpty()) return;
        replaceOwner(projectId, type, id, groupIds, false);
    }

    @Transactional
    public ScanModuleEntity create(Long projectId, String displayName) {
        projects.lockCapabilityChanges(projectId);
        String name = classificationName(displayName);
        for (var group : list(projectId)) if (normalize(group.getDisplayName()).equals(normalize(name))) return group;
        ScanModuleEntity group = new ScanModuleEntity(); group.setProjectId(projectId);
        group.setName("category:" + java.util.UUID.randomUUID()); group.setDisplayName(name); group.setSourceClasses("[]"); modules.insert(group); return group;
    }

    public static String classificationName(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 128 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("业务分类名称须为 1 至 128 个字符");
        return value.trim();
    }

    public void seedHttpApi(Long projectId, Long id, List<String> hints, String kind, String location) {
        if (state(projectId, CatalogSubject.HTTP_API, id) != null || !ownerGroupIds(projectId, CatalogSubject.HTTP_API, id).isEmpty()) return;
        var names = hints == null ? List.<String>of() : hints;
        String sourceClass = controllerClass(kind, location);
        if (names.isEmpty() && sourceClass != null) names = List.of(sourceClass.substring(sourceClass.lastIndexOf('.') + 1));
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (String name : names) {
            ScanModuleEntity group = null;
            for (var candidate : list(projectId)) {
                if (normalize(candidate.getDisplayName()).equals(normalize(name)) || normalize(candidate.getName()).equals(normalize(name))
                        || ((hints == null || hints.isEmpty()) && sourceClass != null && parseClasses(candidate.getSourceClasses()).contains(sourceClass))) { group = candidate; break; }
            }
            if (group == null) group = create(projectId, name);
            if (sourceClass != null && !parseClasses(group.getSourceClasses()).contains(sourceClass)) {
                var sources = new java.util.LinkedHashSet<>(parseClasses(group.getSourceClasses())); sources.add(sourceClass);
                group.setSourceClasses(writeClasses(List.copyOf(sources))); modules.updateById(group);
            }
            ids.add(group.getId());
        }
        seedOwner(projectId, CatalogSubject.HTTP_API, id, List.copyOf(ids));
    }

    private String controllerClass(String kind, String location) {
        if (location == null || !("STARTER_MVC".equals(kind) || "CONTROLLER_SCAN".equals(kind))) return null;
        String[] parts = location.split("#");
        if (parts.length < 2) return null;
        String value = parts.length >= 3 ? parts[1] : parts[0];
        if (value.startsWith("mvc:")) value = value.substring(4);
        return value.matches("[\\p{L}_$][\\p{L}\\p{N}_.$]*") ? value : null;
    }

    public ScanModuleEntity requireGroup(Long projectId, Long groupId) {
        ScanModuleEntity group = groupId == null ? null : modules.selectById(groupId);
        if (group == null || !Objects.equals(projectId, group.getProjectId())) throw new IllegalArgumentException("分组不存在或不属于当前项目");
        return group;
    }

    public List<String> parseClasses(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception malformed) { return List.of(); }
    }

    private String writeClasses(List<String> values) {
        try { return json.writeValueAsString(values); }
        catch (Exception invalid) { throw new IllegalArgumentException("分组来源无法序列化", invalid); }
    }

    private String sourceGroupName(ScanProjectToolEntity tool) {
        if (CapabilitySourceOwnership.isSdkLocation(tool.getSourceLocation())
                && StringUtils.hasText(tool.getCapabilityMetadataJson())) {
            try {
                var metadata = json.readTree(tool.getCapabilityMetadataJson());
                for (String field : List.of("module", "className")) {
                    var value = metadata.get(field);
                    if (value != null && value.isTextual() && StringUtils.hasText(value.textValue())) return value.textValue().trim();
                }
            } catch (Exception malformed) {
                // A legacy projection without valid metadata still follows its source identity.
            }
        }
        if (StringUtils.hasText(tool.getSourceLocation())) {
            String[] parts = tool.getSourceLocation().split("#");
            if (parts.length >= 2 && StringUtils.hasText(parts[1])) return parts[1].trim();
            String name = parts[0].trim(); int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
        return StringUtils.hasText(tool.getHttpMethod()) ? normalize(tool.getHttpMethod()) + "_module" : "default";
    }
    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
