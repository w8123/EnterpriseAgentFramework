package com.enterprise.ai.control.context;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextRuntimeUserMappingControllerTest {

    @Test
    void keepsRuntimeUserMappingRoutesOnControlService() throws Exception {
        Method list = ContextRuntimeUserMappingController.class.getDeclaredMethod(
                "list", String.class, Long.class, String.class, Long.class, String.class, String.class, int.class);
        Method create = ContextRuntimeUserMappingController.class
                .getDeclaredMethod("create", ContextRuntimeUserMappingController.CreateCommand.class);
        Method delete = ContextRuntimeUserMappingController.class.getDeclaredMethod("delete", Long.class);

        assertArrayEquals(new String[] {"/api/context/runtime-user-mappings"},
                list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/runtime-user-mappings"},
                create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/runtime-user-mappings/{id}"},
                delete.getAnnotation(DeleteMapping.class).value());
    }

    @Test
    void listsCreatesAndSoftDeletesRuntimeUserMappings() {
        ContextRuntimeUserMappingMapper mapper = mock(ContextRuntimeUserMappingMapper.class);
        ContextRuntimeUserMappingController controller = new ContextRuntimeUserMappingController(mapper);
        ContextRuntimeUserMappingEntity existing = mapping(7L);
        when(mapper.selectList(any())).thenReturn(List.of(existing));
        when(mapper.selectById(7L)).thenReturn(existing);

        ResponseEntity<List<ContextRuntimeUserMappingController.MappingView>> listed =
                controller.list("default", 1L, "runtime-jsh", null, "bzjs12", "ACTIVE", 20);
        ResponseEntity<ContextRuntimeUserMappingController.MappingView> created =
                controller.create(new ContextRuntimeUserMappingController.CreateCommand(
                        "default",
                        1L,
                        "",
                        "global-jsh",
                        "external-jsh",
                        null,
                        "bzjs12"));
        ResponseEntity<ContextRuntimeUserMappingController.MappingView> deleted = controller.delete(7L);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals("runtime-jsh", listed.getBody().get(0).runtimeUserId());
        assertEquals("global-jsh", created.getBody().runtimeUserId());
        assertEquals("DELETED", deleted.getBody().status());
        verify(mapper).insert(any());
        verify(mapper).updateById(any());
    }

    private ContextRuntimeUserMappingEntity mapping(Long id) {
        ContextRuntimeUserMappingEntity entity = new ContextRuntimeUserMappingEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setPlatformUserId(1L);
        entity.setRuntimeUserId("runtime-jsh");
        entity.setGlobalUserId("global-jsh");
        entity.setExternalUserId("external-jsh");
        entity.setProjectCode("bzjs12");
        entity.setStatus("ACTIVE");
        entity.setCreatedBy("codex");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
