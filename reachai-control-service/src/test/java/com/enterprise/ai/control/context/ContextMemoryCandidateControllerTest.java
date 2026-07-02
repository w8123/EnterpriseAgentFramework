package com.enterprise.ai.control.context;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextMemoryCandidateControllerTest {

    @Test
    void keepsContextMemoryCandidateRoutesOnControlService() throws Exception {
        Method list = ContextMemoryCandidateController.class.getDeclaredMethod("list",
                String.class, String.class, Long.class, String.class, String.class, String.class,
                Long.class, String.class, String.class, String.class, String.class, String.class,
                Boolean.class, Integer.class);
        Method create = ContextMemoryCandidateController.class.getDeclaredMethod("create",
                ContextMemoryCandidateController.CandidateCommand.class);
        Method approve = ContextMemoryCandidateController.class.getDeclaredMethod("approve", Long.class,
                ContextMemoryCandidateController.ReviewCommand.class);
        Method reject = ContextMemoryCandidateController.class.getDeclaredMethod("reject", Long.class,
                ContextMemoryCandidateController.ReviewCommand.class);
        Method update = ContextMemoryCandidateController.class.getDeclaredMethod("update", Long.class,
                ContextMemoryCandidateController.CandidateUpdateCommand.class);
        Method batchApprove = ContextMemoryCandidateController.class.getDeclaredMethod("batchApprove",
                ContextMemoryCandidateController.BatchReviewCommand.class);
        Method batchReject = ContextMemoryCandidateController.class.getDeclaredMethod("batchReject",
                ContextMemoryCandidateController.BatchReviewCommand.class);

        assertArrayEquals(new String[] {"/api/context/memory/candidates"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/memory/candidates"}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/memory/candidates/{id}/approve"}, approve.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/memory/candidates/{id}/reject"}, reject.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/memory/candidates/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/memory/candidates/batch/approve"}, batchApprove.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/context/memory/candidates/batch/reject"}, batchReject.getAnnotation(PostMapping.class).value());
    }

    @Test
    void managesCandidateBufferAndReviewStateTransitions() {
        ContextMemoryCandidateMapper candidateMapper = mock(ContextMemoryCandidateMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextNamespaceMapper namespaceMapper = mock(ContextNamespaceMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        ContextMemoryCandidateController controller =
                new ContextMemoryCandidateController(candidateMapper, itemMapper, namespaceMapper, auditMapper);
        ContextMemoryCandidateEntity candidate = candidate(7L);
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate));
        when(candidateMapper.selectById(7L)).thenReturn(candidate(7L), candidate(7L), candidate(7L));
        when(candidateMapper.selectById(8L)).thenReturn(candidate(8L), candidate(8L));
        when(namespaceMapper.selectById(3L)).thenReturn(namespace(3L));

        ResponseEntity<List<ContextMemoryCandidateController.CandidateView>> listed =
                controller.list("default", "bzjs12", null, "PROJECT_DEV", null, "PENDING",
                        null, null, null, null, null, null, false, 20);
        ResponseEntity<ContextMemoryCandidateController.CandidateView> created =
                controller.create(new ContextMemoryCandidateController.CandidateCommand(
                        "default", null, "bzjs12", 3L, null, "PROJECT_DEV", "RULE",
                        "Boundary rule", "Use Control for public APIs.", "summary", "reason",
                        "MANUAL", "doc", "trace-1", null, "user-1", null, null,
                        null, null, null, null, null, null, BigDecimal.valueOf(0.8), "HIGH",
                        "PROJECT", "codex", null, "{}"));
        ResponseEntity<ContextMemoryCandidateController.CandidateView> updated =
                controller.update(7L, new ContextMemoryCandidateController.CandidateUpdateCommand(
                        "default", "bzjs12", null, "PROJECT_DEV", null, "codex", "fix",
                        3L, null, "RULE", "Boundary rule v2", "Use Control BFF.", null,
                        null, "MANUAL", "doc", null, null, null, null, BigDecimal.valueOf(0.85),
                        "VERIFIED", "PROJECT", null, "{}"));
        ResponseEntity<ContextMemoryCandidateController.CandidateView> approved =
                controller.approve(7L, new ContextMemoryCandidateController.ReviewCommand(
                        "default", "bzjs12", null, "PROJECT_DEV", null, "codex",
                        "accept", BigDecimal.valueOf(0.9), "VERIFIED"));
        ResponseEntity<ContextMemoryCandidateController.CandidateView> rejected =
                controller.reject(8L, new ContextMemoryCandidateController.ReviewCommand(
                        "default", "bzjs12", null, "PROJECT_DEV", null, "codex",
                        "duplicate", null, null));
        ResponseEntity<List<ContextMemoryCandidateController.CandidateView>> batchApproved =
                controller.batchApprove(new ContextMemoryCandidateController.BatchReviewCommand(
                        "default", "bzjs12", null, "PROJECT_DEV", null, "codex",
                        "accept", BigDecimal.valueOf(0.9), "VERIFIED", List.of(7L)));
        ResponseEntity<List<ContextMemoryCandidateController.CandidateView>> batchRejected =
                controller.batchReject(new ContextMemoryCandidateController.BatchReviewCommand(
                        "default", "bzjs12", null, "PROJECT_DEV", null, "codex",
                        "duplicate", null, null, List.of(8L)));

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals("ctx-candidate-7", listed.getBody().get(0).candidateKey());
        assertEquals("Boundary rule", created.getBody().title());
        assertEquals("Boundary rule v2", updated.getBody().title());
        assertEquals("APPROVED", approved.getBody().status());
        assertEquals("REJECTED", rejected.getBody().status());
        assertEquals(1, batchApproved.getBody().size());
        assertEquals(1, batchRejected.getBody().size());
        verify(candidateMapper).insert(any());
        verify(itemMapper, times(2)).insert(any());
        verify(candidateMapper, times(5)).updateById(any());
        verify(auditMapper, times(6)).insert(any());
    }

    private ContextNamespaceEntity namespace(Long id) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(id);
        entity.setNamespaceKey("ctx:default:project:bzjs12");
        return entity;
    }

    private ContextMemoryCandidateEntity candidate(Long id) {
        ContextMemoryCandidateEntity entity = new ContextMemoryCandidateEntity();
        entity.setId(id);
        entity.setCandidateKey("ctx-candidate-" + id);
        entity.setTenantId("default");
        entity.setProjectCode("bzjs12");
        entity.setNamespaceId(3L);
        entity.setNamespaceKey("ctx:default:project:bzjs12");
        entity.setMemoryLane("PROJECT_DEV");
        entity.setCandidateType("RULE");
        entity.setTitle("Boundary rule");
        entity.setContent("Use Control for public APIs.");
        entity.setSummary("summary");
        entity.setReason("reason");
        entity.setSourceType("MANUAL");
        entity.setSourceRef("doc");
        entity.setTraceId("trace-1");
        entity.setUserId("user-1");
        entity.setConfidence(BigDecimal.valueOf(0.8));
        entity.setTrustLevel("HIGH");
        entity.setVisibility("PROJECT");
        entity.setStatus("PENDING");
        entity.setProposedBy("codex");
        entity.setMetadataJson("{}");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
