package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryProjectCatalog.ProjectOption;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Two fixed layers, personal and one per project, each holding owner-defined groups. A layer gets
 * 「偏好」 and 「备注」 the first time it is used; memories written before groups existed are filed into
 * them by type. A group's kind decides recall: preferences are always handed to the AI, notes only
 * when relevant.
 */
@Service
public class PersonalMemoryGroupService {

    public static final String KIND_PREFERENCE = "PREFERENCE";
    public static final String KIND_NOTE = "NOTE";
    static final int MAX_GROUPS_PER_LAYER = 30;
    private static final int MAX_NAME_CHARS = 64;
    private static final Set<String> PREFERENCE_TYPES = Set.of("PREFERENCE", "RULE");

    private final PersonalMemoryGroupMapper groupMapper;
    private final PersonalMemoryMapper memoryMapper;
    private final ObjectProvider<PersonalMemoryProjectCatalog> projectCatalog;

    public PersonalMemoryGroupService(PersonalMemoryGroupMapper groupMapper,
                                      PersonalMemoryMapper memoryMapper,
                                      ObjectProvider<PersonalMemoryProjectCatalog> projectCatalog) {
        this.groupMapper = groupMapper;
        this.memoryMapper = memoryMapper;
        this.projectCatalog = projectCatalog;
    }

    /** Every layer the owner has, personal first, with group counts and the projects still addable. */
    @Transactional
    public TreeView tree(PersonalMemoryPrincipal principal) {
        Map<String, String> projectNames = projectNames();
        ensureLayer(principal, PersonalMemoryScope.PERSONAL, null);
        // A project layer that has memories but lost its groups (or predates them) gets them back.
        for (Map<String, Object> row : groupMapper.countByGroup(principal.tenantId(), principal.runtimeUserId(), principal.ownerDomain())) {
            String scopeKey = String.valueOf(row.get("scopeKey"));
            if (row.get("groupId") == null && scopeKey.startsWith("project:")) {
                PersonalMemoryScope scope = PersonalMemoryScope.of(scopeKey.substring("project:".length()));
                ensureLayer(principal, scope, projectNames.get(scope.projectCode()));
            }
        }
        Map<Long, Long> counts = new HashMap<>();
        long total = 0;
        for (Map<String, Object> row : groupMapper.countByGroup(principal.tenantId(), principal.runtimeUserId(), principal.ownerDomain())) {
            long count = ((Number) row.get("total")).longValue();
            total += count;
            if (row.get("groupId") instanceof Number id) counts.put(id.longValue(), count);
        }
        Map<String, List<PersonalMemoryGroupEntity>> byLayer = new LinkedHashMap<>();
        for (PersonalMemoryGroupEntity group : ownedGroups(principal)) {
            byLayer.computeIfAbsent(group.getScopeKey(), ignored -> new ArrayList<>()).add(group);
        }
        List<LayerView> layers = new ArrayList<>();
        for (Map.Entry<String, List<PersonalMemoryGroupEntity>> entry : byLayer.entrySet()) {
            PersonalMemoryGroupEntity first = entry.getValue().get(0);
            String name = first.getProjectCode() == null ? "个人记忆"
                    : firstText(projectNames.get(first.getProjectCode()), first.getProjectName(), first.getProjectCode());
            List<GroupView> groups = entry.getValue().stream()
                    .map(group -> view(group, counts.getOrDefault(group.getId(), 0L))).toList();
            layers.add(new LayerView(first.getProjectCode() == null ? "PERSONAL" : "PROJECT", entry.getKey(),
                    first.getProjectCode(), name, groups.stream().mapToLong(GroupView::count).sum(), groups));
        }
        layers.sort(Comparator.comparing((LayerView layer) -> !"PERSONAL".equals(layer.layer()))
                .thenComparing(LayerView::name));
        Set<String> opened = byLayer.keySet();
        List<ProjectOption> addable = projectNames.entrySet().stream()
                .filter(entry -> !opened.contains(PersonalMemoryScope.of(entry.getKey()).key()))
                .map(entry -> new ProjectOption(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ProjectOption::name))
                .toList();
        return new TreeView(layers, addable, total);
    }

    /** Opens a project layer with its default groups. Opening an existing layer changes nothing. */
    @Transactional
    public LayerView addProject(PersonalMemoryPrincipal principal, String projectCode) {
        PersonalMemoryScope scope = PersonalMemoryScope.of(projectCode);
        if (scope.personal()) {
            throw badRequest("请选择一个项目");
        }
        String name = projectNames().get(scope.projectCode());
        List<GroupView> groups = ensureLayer(principal, scope, name).stream().map(group -> view(group, 0L)).toList();
        return new LayerView("PROJECT", scope.key(), scope.projectCode(), firstText(name, scope.projectCode()),
                0L, groups);
    }

    @Transactional
    public GroupView createGroup(PersonalMemoryPrincipal principal, CreateGroupCommand command) {
        if (command == null) throw badRequest("group is required");
        PersonalMemoryScope scope = PersonalMemoryScope.of(command.projectCode());
        String name = validName(command.name());
        List<PersonalMemoryGroupEntity> existing = ensureLayer(principal, scope,
                scope.personal() ? null : projectNames().get(scope.projectCode()));
        if (existing.size() >= MAX_GROUPS_PER_LAYER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "每一层最多 " + MAX_GROUPS_PER_LAYER + " 个分组");
        }
        if (existing.stream().anyMatch(group -> group.getName().equals(name))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已经有同名分组");
        }
        int sort = existing.stream().mapToInt(group -> group.getSortOrder() == null ? 0 : group.getSortOrder())
                .max().orElse(0) + 10;
        PersonalMemoryGroupEntity group = newGroup(principal, scope, existing.get(0).getProjectName(), name,
                validKind(command.kind()), sort);
        try {
            groupMapper.insert(group);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已经有同名分组");
        }
        return view(group, 0L);
    }

    @Transactional
    public GroupView updateGroup(PersonalMemoryPrincipal principal, Long id, UpdateGroupCommand command) {
        if (command == null) throw badRequest("group update is required");
        PersonalMemoryGroupEntity group = requireOwned(principal, id, true);
        if (command.name() != null) group.setName(validName(command.name()));
        String previousKind = group.getKind();
        if (command.kind() != null) group.setKind(validKind(command.kind()));
        group.setUpdatedAt(LocalDateTime.now());
        try {
            groupMapper.updateById(group);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已经有同名分组");
        }
        if (!Objects.equals(previousKind, group.getKind())) {
            // Recall priority follows the group, so the memories inside follow its new kind.
            for (PersonalMemoryEntity memory : memoryMapper.selectList(owned(principal)
                    .eq(PersonalMemoryEntity::getGroupId, group.getId()))) {
                String type = typeFor(group.getKind(), memory.getMemoryType());
                if (!type.equals(memory.getMemoryType())) {
                    memory.setMemoryType(type);
                    memoryMapper.updateById(memory);
                }
            }
        }
        return view(group, memoryMapper.selectCount(owned(principal).eq(PersonalMemoryEntity::getGroupId, id)));
    }

    /** Deletes an empty group. A layer whose last group is deleted disappears from the tree. */
    @Transactional
    public void deleteGroup(PersonalMemoryPrincipal principal, Long id) {
        PersonalMemoryGroupEntity group = requireOwned(principal, id, true);
        long inside = memoryMapper.selectCount(owned(principal).eq(PersonalMemoryEntity::getGroupId, id));
        if (inside > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "分组里还有 " + inside + " 条记忆，请先删除或移到其他分组");
        }
        if (group.getProjectCode() == null && ownedGroups(principal).stream()
                .filter(other -> other.getProjectCode() == null).count() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "个人记忆至少保留一个分组");
        }
        groupMapper.deleteById(id);
    }

    /**
     * The group a new or moved memory goes into: the named group, or in the requested layer the first
     * group whose kind matches the memory type.
     */
    @Transactional
    public PersonalMemoryGroupEntity target(PersonalMemoryPrincipal principal, PersonalMemoryScope scope,
                                            Long groupId, String type) {
        if (groupId != null) {
            return requireOwned(principal, groupId, false);
        }
        List<PersonalMemoryGroupEntity> groups = ensureLayer(principal, scope,
                scope.personal() ? null : projectNames().get(scope.projectCode()));
        String kind = kindFor(type);
        return groups.stream().filter(group -> kind.equals(group.getKind())).findFirst().orElse(groups.get(0));
    }

    @Transactional(readOnly = true)
    public Map<Long, PersonalMemoryGroupEntity> groupsById(PersonalMemoryPrincipal principal) {
        Map<Long, PersonalMemoryGroupEntity> result = new HashMap<>();
        ownedGroups(principal).forEach(group -> result.put(group.getId(), group));
        return result;
    }

    @Transactional
    public int deleteAll(PersonalMemoryPrincipal principal) {
        return groupMapper.delete(Wrappers.<PersonalMemoryGroupEntity>lambdaQuery()
                .eq(PersonalMemoryGroupEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryGroupEntity::getOwnerId, principal.runtimeUserId())
                .eq(PersonalMemoryGroupEntity::getOwnerDomain, principal.ownerDomain()));
    }

    /** Legacy cross-domain erasure must not claim completion while scoped groups exist. */
    @Transactional(readOnly = true)
    public boolean hasScopedOwnerGroups(String tenantId, String userId) {
        return groupMapper.selectCount(Wrappers.<PersonalMemoryGroupEntity>lambdaQuery()
                .eq(PersonalMemoryGroupEntity::getTenantId, tenantId)
                .eq(PersonalMemoryGroupEntity::getOwnerId, userId)
                .ne(PersonalMemoryGroupEntity::getOwnerDomain, PersonalMemoryPrincipal.LEGACY_DOMAIN)) > 0;
    }

    /** The memory type stored for a memory in a group of this kind; keeps a finer type when it fits. */
    public static String typeFor(String kind, String requestedType) {
        String type = StringUtils.hasText(requestedType) ? requestedType.trim().toUpperCase(Locale.ROOT) : null;
        if (KIND_PREFERENCE.equals(kind)) {
            return type != null && PREFERENCE_TYPES.contains(type) ? type : "PREFERENCE";
        }
        return type != null && !PREFERENCE_TYPES.contains(type) && PersonalMemoryService.ALLOWED_TYPES.contains(type)
                ? type : "NOTE";
    }

    static String kindFor(String type) {
        return type != null && PREFERENCE_TYPES.contains(type.trim().toUpperCase(Locale.ROOT))
                ? KIND_PREFERENCE : KIND_NOTE;
    }

    private List<PersonalMemoryGroupEntity> ensureLayer(PersonalMemoryPrincipal principal, PersonalMemoryScope scope,
                                                        String projectName) {
        List<PersonalMemoryGroupEntity> groups = layerGroups(principal, scope);
        if (groups.isEmpty()) {
            String name = scope.personal() ? null : firstText(projectName, scope.projectCode());
            for (PersonalMemoryGroupEntity group : List.of(
                    newGroup(principal, scope, name, "偏好", KIND_PREFERENCE, 10),
                    newGroup(principal, scope, name, "备注", KIND_NOTE, 20))) {
                try {
                    groupMapper.insert(group);
                } catch (DuplicateKeyException concurrentlyCreated) {
                    // Another request opened the layer at the same time; its groups are equivalent.
                }
            }
            groups = layerGroups(principal, scope);
        }
        fileUngrouped(principal, scope, groups);
        return groups;
    }

    private void fileUngrouped(PersonalMemoryPrincipal principal, PersonalMemoryScope scope,
                               List<PersonalMemoryGroupEntity> groups) {
        PersonalMemoryGroupEntity preference = groups.stream()
                .filter(group -> KIND_PREFERENCE.equals(group.getKind())).findFirst().orElse(groups.get(0));
        PersonalMemoryGroupEntity note = groups.stream()
                .filter(group -> KIND_NOTE.equals(group.getKind())).findFirst().orElse(groups.get(0));
        memoryMapper.update(null, Wrappers.<PersonalMemoryEntity>lambdaUpdate()
                .eq(PersonalMemoryEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryEntity::getOwnerId, principal.runtimeUserId())
                .eq(PersonalMemoryEntity::getOwnerDomain, principal.ownerDomain())
                .eq(PersonalMemoryEntity::getScopeKey, scope.key())
                .isNull(PersonalMemoryEntity::getGroupId)
                .in(PersonalMemoryEntity::getMemoryType, PREFERENCE_TYPES)
                .set(PersonalMemoryEntity::getGroupId, preference.getId()));
        memoryMapper.update(null, Wrappers.<PersonalMemoryEntity>lambdaUpdate()
                .eq(PersonalMemoryEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryEntity::getOwnerId, principal.runtimeUserId())
                .eq(PersonalMemoryEntity::getOwnerDomain, principal.ownerDomain())
                .eq(PersonalMemoryEntity::getScopeKey, scope.key())
                .isNull(PersonalMemoryEntity::getGroupId)
                .set(PersonalMemoryEntity::getGroupId, note.getId()));
    }

    private PersonalMemoryGroupEntity requireOwned(PersonalMemoryPrincipal principal, Long id, boolean forUpdate) {
        PersonalMemoryGroupEntity group = id == null ? null
                : forUpdate ? groupMapper.selectByIdForUpdate(id) : groupMapper.selectById(id);
        if (group == null || !principal.tenantId().equals(group.getTenantId())
                || !principal.runtimeUserId().equals(group.getOwnerId())
                || !principal.ownerDomain().equals(group.getOwnerDomain())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分组不存在");
        }
        return group;
    }

    private List<PersonalMemoryGroupEntity> layerGroups(PersonalMemoryPrincipal principal, PersonalMemoryScope scope) {
        return groupMapper.selectList(Wrappers.<PersonalMemoryGroupEntity>lambdaQuery()
                .eq(PersonalMemoryGroupEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryGroupEntity::getOwnerId, principal.runtimeUserId())
                .eq(PersonalMemoryGroupEntity::getOwnerDomain, principal.ownerDomain())
                .eq(PersonalMemoryGroupEntity::getScopeKey, scope.key())
                .orderByAsc(PersonalMemoryGroupEntity::getSortOrder)
                .orderByAsc(PersonalMemoryGroupEntity::getId));
    }

    private List<PersonalMemoryGroupEntity> ownedGroups(PersonalMemoryPrincipal principal) {
        return groupMapper.selectList(Wrappers.<PersonalMemoryGroupEntity>lambdaQuery()
                .eq(PersonalMemoryGroupEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryGroupEntity::getOwnerId, principal.runtimeUserId())
                .eq(PersonalMemoryGroupEntity::getOwnerDomain, principal.ownerDomain())
                .orderByAsc(PersonalMemoryGroupEntity::getScopeKey)
                .orderByAsc(PersonalMemoryGroupEntity::getSortOrder)
                .orderByAsc(PersonalMemoryGroupEntity::getId));
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PersonalMemoryEntity> owned(
            PersonalMemoryPrincipal principal) {
        return Wrappers.<PersonalMemoryEntity>lambdaQuery()
                .eq(PersonalMemoryEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryEntity::getOwnerId, principal.runtimeUserId())
                .eq(PersonalMemoryEntity::getOwnerDomain, principal.ownerDomain());
    }

    private Map<String, String> projectNames() {
        Map<String, String> names = new LinkedHashMap<>();
        PersonalMemoryProjectCatalog catalog = projectCatalog.getIfAvailable();
        if (catalog != null) {
            for (ProjectOption option : catalog.projects()) {
                if (StringUtils.hasText(option.projectCode())) {
                    names.putIfAbsent(option.projectCode().trim(),
                            firstText(option.name(), option.projectCode().trim()));
                }
            }
        }
        return names;
    }

    private static PersonalMemoryGroupEntity newGroup(PersonalMemoryPrincipal principal, PersonalMemoryScope scope,
                                                      String projectName, String name, String kind, int sort) {
        LocalDateTime now = LocalDateTime.now();
        PersonalMemoryGroupEntity group = new PersonalMemoryGroupEntity();
        group.setTenantId(principal.tenantId());
        group.setOwnerId(principal.runtimeUserId());
        group.setOwnerDomain(principal.ownerDomain());
        group.setScopeKey(scope.key());
        group.setProjectCode(scope.projectCode());
        group.setProjectName(scope.personal() ? null : projectName);
        group.setName(name);
        group.setKind(kind);
        group.setSortOrder(sort);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        return group;
    }

    static GroupView view(PersonalMemoryGroupEntity group, long count) {
        return new GroupView(group.getId(), group.getName(), group.getKind(), count);
    }

    private static String validName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : null;
        if (name == null) throw badRequest("请填写分组名");
        if (name.length() > MAX_NAME_CHARS) throw badRequest("分组名最多 " + MAX_NAME_CHARS + " 个字");
        return name;
    }

    private static String validKind(String value) {
        if (!StringUtils.hasText(value)) return KIND_NOTE;
        String kind = value.trim().toUpperCase(Locale.ROOT);
        if (!KIND_PREFERENCE.equals(kind) && !KIND_NOTE.equals(kind)) {
            throw badRequest("kind must be PREFERENCE or NOTE");
        }
        return kind;
    }

    private static String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    /** {@code projectCode} empty means the personal layer. {@code kind} defaults to NOTE. */
    public record CreateGroupCommand(String projectCode, String name, String kind) {
    }

    public record UpdateGroupCommand(String name, String kind) {
    }

    public record AddProjectCommand(String projectCode) {
    }

    public record GroupView(Long id, String name, String kind, long count) {
    }

    /** {@code layer} is PERSONAL or PROJECT; {@code scope} is the filter value for the memory list. */
    public record LayerView(String layer, String scope, String projectCode, String name, long count,
                            List<GroupView> groups) {
    }

    public record TreeView(List<LayerView> layers, List<ProjectOption> addableProjects, long total) {
    }
}
