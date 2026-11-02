package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryProjectCatalog.ProjectOption;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PersonalMemoryGroupServiceTest {

    private static final PersonalMemoryPrincipal OWNER =
            new PersonalMemoryPrincipal("default", "user-1", null, null, null);

    @BeforeAll
    static void initializeMyBatisMetadata() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm"), PersonalMemoryEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pm-group"),
                PersonalMemoryGroupEntity.class);
    }

    @Test
    void firstUseOfALayerCreatesPreferenceAndNoteAndFilesOldMemories() {
        Fixture fixture = fixture();
        List<PersonalMemoryGroupEntity> stored = new ArrayList<>();
        doAnswer(invocation -> {
            PersonalMemoryGroupEntity group = invocation.getArgument(0);
            group.setId(10L + stored.size());
            stored.add(group);
            return 1;
        }).when(fixture.groupMapper).insert(any(PersonalMemoryGroupEntity.class));
        when(fixture.groupMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())
                .thenAnswer(invocation -> List.copyOf(stored));

        PersonalMemoryGroupEntity target = fixture.service.target(OWNER, PersonalMemoryScope.PERSONAL, null, "RULE");

        assertEquals(List.of("偏好", "备注"), stored.stream().map(PersonalMemoryGroupEntity::getName).toList());
        assertEquals(List.of("PREFERENCE", "NOTE"), stored.stream().map(PersonalMemoryGroupEntity::getKind).toList());
        assertEquals("偏好", target.getName());
        // Ungrouped memories are filed twice: preferences/rules first, then everything else.
        verify(fixture.memoryMapper, times(2)).update(isNull(), any(Wrapper.class));
    }

    @Test
    void openingAProjectLayerUsesTheSystemName() {
        Fixture fixture = fixture();
        List<PersonalMemoryGroupEntity> stored = new ArrayList<>();
        doAnswer(invocation -> {
            PersonalMemoryGroupEntity group = invocation.getArgument(0);
            group.setId(20L + stored.size());
            stored.add(group);
            return 1;
        }).when(fixture.groupMapper).insert(any(PersonalMemoryGroupEntity.class));
        when(fixture.groupMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())
                .thenAnswer(invocation -> List.copyOf(stored));

        PersonalMemoryGroupService.LayerView layer = fixture.service.addProject(OWNER, "yudao-erp");

        assertEquals("PROJECT", layer.layer());
        assertEquals("project:yudao-erp", layer.scope());
        assertEquals("芋道 ERP", layer.name());
        assertEquals(2, layer.groups().size());
        assertEquals("芋道 ERP", stored.get(0).getProjectName());
        assertEquals("yudao-erp", stored.get(0).getProjectCode());
    }

    @Test
    void aGroupWithMemoriesCannotBeDeleted() {
        Fixture fixture = fixture();
        when(fixture.groupMapper.selectByIdForUpdate(5L)).thenReturn(group(5L, "user-1", null, "常用仓库", "NOTE"));
        when(fixture.memoryMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> fixture.service.deleteGroup(OWNER, 5L));

        assertEquals(409, error.getStatusCode().value());
        verify(fixture.groupMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void anotherOwnersGroupIsNotFound() {
        Fixture fixture = fixture();
        when(fixture.groupMapper.selectByIdForUpdate(5L)).thenReturn(group(5L, "victim", null, "备注", "NOTE"));

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> fixture.service.deleteGroup(OWNER, 5L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> fixture.service.updateGroup(OWNER, 5L,
                        new PersonalMemoryGroupService.UpdateGroupCommand("x", null))).getStatusCode().value());
    }

    @Test
    void sameUserIdInAnotherBusinessDomainCannotChangeGroup() {
        Fixture fixture = fixture();
        var group = group(5L, "user-1", null, "备注", "NOTE");
        group.setOwnerDomain("BUSINESS:erp-a");
        when(fixture.groupMapper.selectByIdForUpdate(5L)).thenReturn(group);
        var other = PersonalMemoryPrincipal.business("default", "user-1", "erp-b");
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> fixture.service.deleteGroup(other, 5L)).getStatusCode().value());
        verify(fixture.groupMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void groupKindDecidesTheStoredType() {
        assertEquals("PREFERENCE", PersonalMemoryGroupService.typeFor("PREFERENCE", null));
        assertEquals("RULE", PersonalMemoryGroupService.typeFor("PREFERENCE", "RULE"));
        assertEquals("PREFERENCE", PersonalMemoryGroupService.typeFor("PREFERENCE", "FACT"));
        assertEquals("NOTE", PersonalMemoryGroupService.typeFor("NOTE", "PREFERENCE"));
        assertEquals("FACT", PersonalMemoryGroupService.typeFor("NOTE", "FACT"));
    }

    @Test
    void changingAGroupToPreferenceMovesItsMemoriesWithIt() {
        Fixture fixture = fixture();
        when(fixture.groupMapper.selectByIdForUpdate(5L)).thenReturn(group(5L, "user-1", null, "回复风格", "NOTE"));
        PersonalMemoryEntity memory = new PersonalMemoryEntity();
        memory.setId(9L);
        memory.setMemoryType("NOTE");
        when(fixture.memoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(memory));

        PersonalMemoryGroupService.GroupView view = fixture.service.updateGroup(OWNER, 5L,
                new PersonalMemoryGroupService.UpdateGroupCommand(null, "PREFERENCE"));

        assertEquals("PREFERENCE", view.kind());
        ArgumentCaptor<PersonalMemoryEntity> updated = ArgumentCaptor.forClass(PersonalMemoryEntity.class);
        verify(fixture.memoryMapper).updateById(updated.capture());
        assertEquals("PREFERENCE", updated.getValue().getMemoryType());
    }

    private static PersonalMemoryGroupEntity group(Long id, String owner, String projectCode, String name, String kind) {
        PersonalMemoryGroupEntity group = new PersonalMemoryGroupEntity();
        group.setId(id);
        group.setTenantId("default");
        group.setOwnerId(owner);
        group.setOwnerDomain("LEGACY");
        group.setScopeKey(PersonalMemoryScope.of(projectCode).key());
        group.setProjectCode(projectCode);
        group.setName(name);
        group.setKind(kind);
        group.setSortOrder(10);
        return group;
    }

    private static Fixture fixture() {
        PersonalMemoryGroupMapper groupMapper = mock(PersonalMemoryGroupMapper.class);
        PersonalMemoryMapper memoryMapper = mock(PersonalMemoryMapper.class);
        ObjectProvider<PersonalMemoryProjectCatalog> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(() -> List.of(new ProjectOption("yudao-erp", "芋道 ERP")));
        when(groupMapper.countByGroup(any(), any(), any())).thenReturn(List.<Map<String, Object>>of());
        return new Fixture(new PersonalMemoryGroupService(groupMapper, memoryMapper, provider), groupMapper, memoryMapper);
    }

    private record Fixture(PersonalMemoryGroupService service,
                           PersonalMemoryGroupMapper groupMapper,
                           PersonalMemoryMapper memoryMapper) {
    }
}
