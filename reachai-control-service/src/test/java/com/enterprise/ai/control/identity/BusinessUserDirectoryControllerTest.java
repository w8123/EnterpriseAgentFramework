package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessUserDirectoryControllerTest {

    @Test
    void listsBusinessUsersWithIdentityAndRoleSummary() {
        BusinessUserMapper userMapper = mock(BusinessUserMapper.class);
        ExternalUserBindingMapper bindingMapper = mock(ExternalUserBindingMapper.class);
        ExternalUserRoleBindingMapper roleBindingMapper = mock(ExternalUserRoleBindingMapper.class);
        BusinessUserDirectoryController controller = new BusinessUserDirectoryController(
                userMapper,
                bindingMapper,
                roleBindingMapper);
        BusinessUserEntity user = businessUser(7L, "global-jsh", "jsh");
        ExternalUserBindingEntity binding = binding(11L, 7L, "bzjs12", "jsh");
        ExternalUserRoleBindingEntity role = roleBinding(21L, 7L, "APPROVER", "审批人");
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(roleBindingMapper.selectList(any())).thenReturn(List.of(role));

        ResponseEntity<BusinessUserDirectoryController.PageResult<BusinessUserDirectoryController.BusinessUserView>> response =
                controller.listBusinessUsers(1, 20, "default", "jsh", "ACTIVE");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1L, response.getBody().total());
        assertEquals("global-jsh", response.getBody().records().get(0).globalUserId());
        assertEquals(1, response.getBody().records().get(0).bindingCount());
        assertEquals(List.of("bzjs12:jsh"), response.getBody().records().get(0).externalIdentities());
        assertEquals(List.of("APPROVER"), response.getBody().records().get(0).roleCodes());
    }

    @Test
    void updatesBusinessUserAndSavesExternalIdentity() {
        BusinessUserMapper userMapper = mock(BusinessUserMapper.class);
        ExternalUserBindingMapper bindingMapper = mock(ExternalUserBindingMapper.class);
        ExternalUserRoleBindingMapper roleBindingMapper = mock(ExternalUserRoleBindingMapper.class);
        BusinessUserDirectoryController controller = new BusinessUserDirectoryController(
                userMapper,
                bindingMapper,
                roleBindingMapper);
        BusinessUserEntity user = businessUser(7L, "global-jsh", "jsh");
        when(userMapper.selectById(7L)).thenReturn(user);
        ExternalUserBindingEntity binding = binding(11L, 7L, "bzjs12", "jsh");
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(bindingMapper.selectOne(any())).thenReturn(binding);
        ExternalUserRoleBindingEntity role = roleBinding(21L, 7L, "APPROVER", "审批人");
        when(roleBindingMapper.selectList(any())).thenReturn(List.of(role));

        ResponseEntity<BusinessUserDirectoryController.BusinessUserView> updated =
                controller.updateBusinessUser(7L, new BusinessUserDirectoryController.BusinessUserUpdateCommand(
                        "global-jsh",
                        "Jason",
                        "jsh@example.com",
                        "13800000000",
                        "ACTIVE"));
        ResponseEntity<List<BusinessUserDirectoryController.ExternalIdentityView>> identities =
                controller.listBusinessUserIdentities(7L);
        ResponseEntity<BusinessUserDirectoryController.ExternalIdentityView> saved =
                controller.saveBusinessUserIdentity(7L, new BusinessUserDirectoryController.ExternalIdentityCommand(
                        11L,
                        "bzjs12",
                        "jsh",
                        "Jason",
                        "D01",
                        "研发部",
                        "ACTIVE",
                        List.of("APPROVER")));

        assertEquals("Jason", updated.getBody().displayName());
        assertEquals("jsh", identities.getBody().get(0).externalUserId());
        assertEquals(List.of("APPROVER"), saved.getBody().roles().stream().map(
                BusinessUserDirectoryController.ExternalRoleView::roleCode).toList());
        verify(userMapper).updateById(any());
        verify(bindingMapper).updateById(any());
        verify(roleBindingMapper).delete(any());
        verify(roleBindingMapper).insert(any());
    }

    private BusinessUserEntity businessUser(Long id, String globalUserId, String displayName) {
        BusinessUserEntity entity = new BusinessUserEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setGlobalUserId(globalUserId);
        entity.setDisplayName(displayName);
        entity.setStatus("ACTIVE");
        entity.setSource("SDK");
        return entity;
    }

    private ExternalUserBindingEntity binding(Long id, Long userId, String appId, String externalUserId) {
        ExternalUserBindingEntity entity = new ExternalUserBindingEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setBusinessUserId(userId);
        entity.setAppId(appId);
        entity.setExternalUserId(externalUserId);
        entity.setExternalUserName(externalUserId);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private ExternalUserRoleBindingEntity roleBinding(Long id, Long userId, String roleCode, String roleName) {
        ExternalUserRoleBindingEntity entity = new ExternalUserRoleBindingEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setBusinessUserId(userId);
        entity.setAppId("bzjs12");
        entity.setExternalUserId("jsh");
        entity.setRoleCode(roleCode);
        entity.setRoleName(roleName);
        entity.setStatus("ACTIVE");
        entity.setSource("SDK");
        return entity;
    }
}
