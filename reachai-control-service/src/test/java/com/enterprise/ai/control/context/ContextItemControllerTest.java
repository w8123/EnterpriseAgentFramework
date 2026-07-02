package com.enterprise.ai.control.context;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextItemControllerTest {

    @Test
    void keepsContextItemRoutesOnControlService() throws Exception {
        Method list = ContextItemController.class.getDeclaredMethod("list",
                String.class, String.class, Long.class, String.class, Long.class, String.class,
                String.class, String.class, Integer.class, Integer.class);
        Method get = ContextItemController.class.getDeclaredMethod("get", Long.class);
        Method create = ContextItemController.class.getDeclaredMethod("create",
                ContextItemController.ItemCommand.class);
        Method update = ContextItemController.class.getDeclaredMethod("update", Long.class,
                ContextItemController.ItemUpdateCommand.class);
        Method revoke = ContextItemController.class.getDeclaredMethod("revoke", Long.class, Map.class);
        Method stale = ContextItemController.class.getDeclaredMethod("stale", Long.class, Map.class);
        Method verifyItem = ContextItemController.class.getDeclaredMethod("verifyItem", Long.class,
                ContextItemController.VerifyCommand.class);
        Method delete = ContextItemController.class.getDeclaredMethod("delete", Long.class, Map.class);
        Method listEvidence = ContextItemController.class.getDeclaredMethod("listEvidence", Long.class);
        Method addEvidence = ContextItemController.class.getDeclaredMethod("addEvidence", Long.class,
                ContextItemController.EvidenceCommand.class);
        Method listBindings = ContextItemController.class.getDeclaredMethod("listBindings", Long.class);

        assertArrayEquals(new String[] {"/api/context/items"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{id}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items"}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{id}/revoke"}, revoke.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{id}/stale"}, stale.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{id}/verify"}, verifyItem.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{id}"}, delete.getAnnotation(DeleteMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{itemId}/evidence"}, listEvidence.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{itemId}/evidence"}, addEvidence.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/items/{itemId}/bindings"}, listBindings.getAnnotation(GetMapping.class).value());
    }

    @Test
    void managesItemsEvidenceBindingsAndStatusTransitions() {
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemController controller = new ContextItemController(itemMapper, evidenceMapper, bindingMapper, namespaceMapper);
        ContextNamespaceEntity namespace = namespace(3L);
        ContextItemEntity item = item(9L);
        ContextEvidenceEntity evidence = evidence(11L);
        ContextBindingEntity binding = binding(12L);
        when(namespaceMapper.selectOne(any())).thenReturn(namespace);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(itemMapper.selectById(9L)).thenReturn(item);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(evidence));
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));

        ResponseEntity<List<ContextItemController.ItemView>> listed =
                controller.list("default", "bzjs12", null, "PROJECT_DEV", 3L, "FACT", "ACTIVE", "rule", 20, 0);
        ResponseEntity<ContextItemController.ItemView> created =
                controller.create(new ContextItemController.ItemCommand(
                        null,
                        "ctx:default:project:bzjs12",
                        "FACT",
                        "PROJECT_DEV",
                        "Rule",
                        "Use the Runtime service for execution.",
                        "Runtime boundary",
                        "{}",
                        "MANUAL",
                        "doc",
                        BigDecimal.valueOf(0.9),
                        "HIGH",
                        "PROJECT",
                        null,
                        null,
                        "default",
                        null,
                        "bzjs12",
                        "codex",
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new ContextItemController.BindingCommand("PROJECT", "bzjs12", null, "default", null, "bzjs12")),
                        List.of(new ContextItemController.EvidenceCommand("MANUAL_NOTE", "doc", "source", "trace-1",
                                BigDecimal.valueOf(0.8), "{}"))));
        ResponseEntity<ContextItemController.ItemView> found = controller.get(9L);
        ResponseEntity<List<ContextItemController.EvidenceView>> evidenceList = controller.listEvidence(9L);
        ResponseEntity<ContextItemController.EvidenceView> addedEvidence =
                controller.addEvidence(9L, new ContextItemController.EvidenceCommand("MANUAL_NOTE", "doc",
                        "source", "trace-1", BigDecimal.valueOf(0.8), "{}"));
        ResponseEntity<List<ContextItemController.BindingView>> bindings = controller.listBindings(9L);
        ResponseEntity<ContextItemController.ItemView> verified =
                controller.verifyItem(9L, new ContextItemController.VerifyCommand(BigDecimal.valueOf(0.95), "VERIFIED"));
        ResponseEntity<ContextItemController.ItemView> revoked = controller.revoke(9L, Map.of("updatedBy", "codex"));
        ResponseEntity<ContextItemController.ItemView> stale = controller.stale(9L, Map.of("updatedBy", "codex"));
        ResponseEntity<ContextItemController.ItemView> deleted = controller.delete(9L, Map.of("updatedBy", "codex"));

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals("ctx-item-9", listed.getBody().get(0).itemKey());
        assertEquals("Rule", created.getBody().title());
        assertEquals("Use the Runtime service for execution.", found.getBody().content());
        assertEquals("MANUAL_NOTE", evidenceList.getBody().get(0).evidenceType());
        assertEquals("MANUAL_NOTE", addedEvidence.getBody().evidenceType());
        assertEquals("PROJECT", bindings.getBody().get(0).bindType());
        assertEquals("VERIFIED", verified.getBody().trustLevel());
        assertEquals("REVOKED", revoked.getBody().status());
        assertEquals("STALE", stale.getBody().status());
        assertEquals("DELETED", deleted.getBody().status());
        verify(itemMapper).insert(any());
        verify(evidenceMapper, times(2)).insert(any());
        verify(bindingMapper).insert(any());
        verify(itemMapper, times(4)).updateById(any());
    }

    @Test
    void returnsEmptyListWhenProjectScopeHasNoContextNamespace() {
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextEvidenceMapper evidenceMapper = mock(ContextEvidenceMapper.class);
        ContextBindingMapper bindingMapper = mock(ContextBindingMapper.class);
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextItemController controller = new ContextItemController(itemMapper, evidenceMapper, bindingMapper, namespaceMapper);
        when(namespaceMapper.selectList(any())).thenReturn(List.of());

        ResponseEntity<List<ContextItemController.ItemView>> listed =
                controller.list("default", "missing", null, "PROJECT_DEV", null, null, "ACTIVE", null, 20, 0);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals(List.of(), listed.getBody());
        verify(itemMapper, never()).selectList(any());
    }

    private ContextNamespaceEntity namespace(Long id) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(id);
        entity.setNamespaceKey("ctx:default:project:bzjs12");
        return entity;
    }

    private ContextItemEntity item(Long id) {
        ContextItemEntity entity = new ContextItemEntity();
        entity.setId(id);
        entity.setItemKey("ctx-item-" + id);
        entity.setNamespaceId(3L);
        entity.setItemType("FACT");
        entity.setMemoryLane("PROJECT_DEV");
        entity.setTitle("Rule");
        entity.setContent("Use the Runtime service for execution.");
        entity.setSummary("Runtime boundary");
        entity.setMetadataJson("{}");
        entity.setSourceType("MANUAL");
        entity.setSourceRef("doc");
        entity.setConfidence(BigDecimal.valueOf(0.9));
        entity.setTrustLevel("HIGH");
        entity.setVisibility("PROJECT");
        entity.setStatus("ACTIVE");
        entity.setCreatedBy("codex");
        entity.setUpdatedBy("codex");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextEvidenceEntity evidence(Long id) {
        ContextEvidenceEntity entity = new ContextEvidenceEntity();
        entity.setId(id);
        entity.setItemId(9L);
        entity.setEvidenceType("MANUAL_NOTE");
        entity.setEvidenceRef("doc");
        entity.setEvidenceExcerpt("source");
        entity.setTraceId("trace-1");
        entity.setConfidence(BigDecimal.valueOf(0.8));
        entity.setMetadataJson("{}");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextBindingEntity binding(Long id) {
        ContextBindingEntity entity = new ContextBindingEntity();
        entity.setId(id);
        entity.setItemId(9L);
        entity.setBindType("PROJECT");
        entity.setBindId("bzjs12");
        entity.setTenantId("default");
        entity.setProjectCode("bzjs12");
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
