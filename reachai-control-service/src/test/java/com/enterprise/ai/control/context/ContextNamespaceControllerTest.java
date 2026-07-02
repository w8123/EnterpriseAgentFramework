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

class ContextNamespaceControllerTest {

    @Test
    void keepsNamespaceRoutesOnControlService() throws Exception {
        Method list = ContextNamespaceController.class.getDeclaredMethod(
                "list", String.class, String.class, Long.class, String.class, String.class);
        Method create = ContextNamespaceController.class
                .getDeclaredMethod("create", ContextNamespaceController.NamespaceCommand.class);
        Method get = ContextNamespaceController.class.getDeclaredMethod("get", Long.class);
        Method delete = ContextNamespaceController.class.getDeclaredMethod("delete", Long.class);

        assertArrayEquals(new String[] {"/api/context/namespaces"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/namespaces"}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/namespaces/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/namespaces/{id}"}, delete.getAnnotation(DeleteMapping.class).value());
    }

    @Test
    void listsCreatesGetsAndSoftDeletesNamespaces() {
        ContextNamespaceMapper mapper = mock(ContextNamespaceMapper.class);
        ContextNamespaceController controller = new ContextNamespaceController(mapper);
        ContextNamespaceEntity namespace = namespace(3L);
        when(mapper.selectList(any())).thenReturn(List.of(namespace));
        when(mapper.selectById(3L)).thenReturn(namespace);

        ResponseEntity<List<ContextNamespaceController.NamespaceView>> listed =
                controller.list("default", "bzjs12", null, "PROJECT", "ACTIVE");
        ResponseEntity<ContextNamespaceController.NamespaceView> created =
                controller.create(new ContextNamespaceController.NamespaceCommand(
                        "",
                        "PROJECT",
                        "default",
                        null,
                        "bzjs12",
                        "PROJECT",
                        "bzjs12",
                        "Project Memory",
                        "Project development context",
                        "codex"));
        ResponseEntity<ContextNamespaceController.NamespaceView> found = controller.get(3L);
        ResponseEntity<ContextNamespaceController.NamespaceView> deleted = controller.delete(3L);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals("ctx:default:project:bzjs12", listed.getBody().get(0).namespaceKey());
        assertEquals("PROJECT", created.getBody().namespaceType());
        assertEquals("Project Memory", found.getBody().displayName());
        assertEquals("DELETED", deleted.getBody().status());
        verify(mapper).insert(any());
        verify(mapper).updateById(any());
    }

    private ContextNamespaceEntity namespace(Long id) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(id);
        entity.setNamespaceKey("ctx:default:project:bzjs12");
        entity.setNamespaceType("PROJECT");
        entity.setTenantId("default");
        entity.setProjectCode("bzjs12");
        entity.setOwnerType("PROJECT");
        entity.setOwnerId("bzjs12");
        entity.setDisplayName("Project Memory");
        entity.setDescription("Project development context");
        entity.setStatus("ACTIVE");
        entity.setCreatedBy("codex");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
