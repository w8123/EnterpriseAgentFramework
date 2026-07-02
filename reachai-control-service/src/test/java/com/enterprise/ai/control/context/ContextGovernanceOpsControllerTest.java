package com.enterprise.ai.control.context;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ContextGovernanceOpsControllerTest {

    @Test
    void keepsContextGovernanceOperationRoutesOnControlService() throws Exception {
        Method audit = ContextGovernanceOpsController.class.getDeclaredMethod("audit",
                String.class, String.class, Long.class, Long.class, Long.class, String.class,
                String.class, String.class, String.class, String.class, String.class, String.class, Integer.class);
        Method summary = ContextGovernanceOpsController.class.getDeclaredMethod("summary",
                String.class, String.class, Long.class, String.class, Boolean.class);
        Method lifecycle = ContextGovernanceOpsController.class.getDeclaredMethod("runLifecycle",
                ContextGovernanceOpsController.LifecycleCommand.class);
        Method query = ContextGovernanceOpsController.class.getDeclaredMethod("query",
                ContextGovernanceOpsController.QueryCommand.class);
        Method pack = ContextGovernanceOpsController.class.getDeclaredMethod("packageContext",
                ContextGovernanceOpsController.PackageCommand.class);

        assertArrayEquals(new String[] {"/api/context/audit"}, audit.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/ops/summary"}, summary.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/lifecycle/run"}, lifecycle.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/query"}, query.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/package"}, pack.getAnnotation(PostMapping.class).value());
    }

    @Test
    void queriesPackagesAndLifecycleRunsWithinControlOwnedContextTables() {
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        ContextMemoryCandidateMapper candidateMapper = mock(ContextMemoryCandidateMapper.class);
        ContextGovernanceOpsController controller =
                new ContextGovernanceOpsController(itemMapper, namespaceMapper, auditMapper, candidateMapper);
        ContextNamespaceEntity namespace = namespace(3L);
        ContextItemEntity rule = item(9L, "RULE", "Use Runtime service for execution.");
        ContextItemEntity page = item(10L, "PAGE_CONTEXT", "SDK wizard page context.");
        ContextAuditEventEntity event = auditEvent(15L);
        ContextMemoryCandidateEntity candidate = candidate(18L);
        when(namespaceMapper.selectList(any())).thenReturn(List.of(namespace));
        when(itemMapper.selectList(any())).thenReturn(List.of(rule, page), List.of(rule), List.of(rule), List.of());
        when(auditMapper.selectList(any())).thenReturn(List.of(event));
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate));

        ResponseEntity<List<ContextGovernanceOpsController.AuditEventView>> audit =
                controller.audit("default", "bzjs12", null, null, null, null, null, null,
                        null, null, null, null, 20);
        ResponseEntity<List<ContextGovernanceOpsController.SearchResultView>> query =
                controller.query(new ContextGovernanceOpsController.QueryCommand(
                        "default", "bzjs12", null, "PROJECT_DEV", "KEYWORD", "Runtime", List.of("RULE"), 5));
        ResponseEntity<ContextGovernanceOpsController.PackageView> packed =
                controller.packageContext(new ContextGovernanceOpsController.PackageCommand(
                        new ContextGovernanceOpsController.QueryCommand(
                                "default", "bzjs12", null, "PROJECT_DEV", "KEYWORD", "Runtime", null, 5),
                        5, 2000));
        ResponseEntity<ContextGovernanceOpsController.LifecycleRunView> dryRun =
                controller.runLifecycle(new ContextGovernanceOpsController.LifecycleCommand(
                        "default", "bzjs12", null, true, false));

        assertEquals(HttpStatus.OK, audit.getStatusCode());
        assertEquals("CREATE", audit.getBody().get(0).eventType());
        assertEquals(HttpStatus.OK, query.getStatusCode());
        assertEquals("RULE", query.getBody().get(0).item().itemType());
        assertEquals(HttpStatus.OK, packed.getStatusCode());
        assertEquals(1, packed.getBody().rules().size());
        assertEquals(HttpStatus.OK, dryRun.getStatusCode());
        assertEquals(1, dryRun.getBody().expiredCandidateCount());
        assertEquals(1, dryRun.getBody().staleItemCount());
        verify(candidateMapper, never()).updateById(any());
        verify(itemMapper, never()).updateById(any());
    }

    private ContextNamespaceEntity namespace(Long id) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setProjectCode("bzjs12");
        entity.setStatus("ACTIVE");
        return entity;
    }

    private ContextItemEntity item(Long id, String itemType, String content) {
        ContextItemEntity entity = new ContextItemEntity();
        entity.setId(id);
        entity.setItemKey("ctx-item-" + id);
        entity.setNamespaceId(3L);
        entity.setItemType(itemType);
        entity.setMemoryLane("PROJECT_DEV");
        entity.setTitle(itemType);
        entity.setContent(content);
        entity.setSourceType("MANUAL");
        entity.setConfidence(BigDecimal.valueOf(0.8));
        entity.setTrustLevel("HIGH");
        entity.setVisibility("PROJECT");
        entity.setStatus("ACTIVE");
        entity.setStaleAfter(LocalDateTime.now().minusDays(1));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextAuditEventEntity auditEvent(Long id) {
        ContextAuditEventEntity entity = new ContextAuditEventEntity();
        entity.setId(id);
        entity.setEventType("CREATE");
        entity.setItemId(9L);
        entity.setNamespaceId(3L);
        entity.setTenantId("default");
        entity.setProjectCode("bzjs12");
        entity.setDecision("ALLOW");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextMemoryCandidateEntity candidate(Long id) {
        ContextMemoryCandidateEntity entity = new ContextMemoryCandidateEntity();
        entity.setId(id);
        entity.setCandidateKey("ctx-candidate-" + id);
        entity.setTenantId("default");
        entity.setProjectCode("bzjs12");
        entity.setMemoryLane("PROJECT_DEV");
        entity.setCandidateType("RULE");
        entity.setContent("candidate");
        entity.setSourceType("SYSTEM");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().minusDays(1));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
