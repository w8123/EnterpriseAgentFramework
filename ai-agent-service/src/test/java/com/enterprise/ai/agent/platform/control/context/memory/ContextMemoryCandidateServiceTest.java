package com.enterprise.ai.agent.platform.control.context.memory;

import com.enterprise.ai.agent.platform.control.context.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextMemoryCandidateServiceTest {

    private ContextMemoryCandidateMapper candidateMapper;
    private ContextItemService itemService;
    private ContextNamespaceService namespaceService;
    private ContextAuditService auditService;
    private ContextMemoryCandidateService service;
    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        candidateMapper = mock(ContextMemoryCandidateMapper.class);
        itemService = mock(ContextItemService.class);
        namespaceService = mock(ContextNamespaceService.class);
        auditService = mock(ContextAuditService.class);
        service = new ContextMemoryCandidateService(
                candidateMapper, itemService, namespaceService, auditService, new ObjectMapper());

        when(candidateMapper.insert(any(ContextMemoryCandidateEntity.class))).thenAnswer(inv -> {
            ContextMemoryCandidateEntity entity = inv.getArgument(0);
            entity.setId(idSeq.getAndIncrement());
            return 1;
        });
        when(auditService.recordWithMetadata(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ContextAuditEventEntity());
    }

    @Test
    void createCandidateSuccessPendingRuntimeUserPrivate() {
        ContextMemoryCandidateResponse created = service.createCandidate(createRequest("user-a", "请记住我喜欢深色模式"));

        assertNotNull(created.getId());
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), created.getStatus());
        assertEquals(MemoryLane.RUNTIME_USER.name(), created.getMemoryLane());
        assertEquals(ContextVisibility.PRIVATE.name(), created.getVisibility());
        assertEquals("user-a", created.getUserId());
        verify(candidateMapper).insert(any(ContextMemoryCandidateEntity.class));
        verify(auditService).recordWithMetadata(eq(ContextAuditEventType.CANDIDATE_CREATE), any(), any(), any(), isNull(), isNull(), any());
    }

    @Test
    void createProjectDevCandidateDoesNotRequireRuntimeUser() {
        ContextMemoryCandidateCreateRequest request = projectDevRequest(
                ContextMemoryCandidateType.WORKFLOW_CONTEXT.name(),
                "Workflow input comes from the order detail route state");
        request.setWorkflowId("orderAuditFlow");
        request.setSourceRef("src/workflows/order-audit.ts");
        request.setUserId("runtime-user");
        request.setGlobalUserId("global-user");
        request.setExternalUserId("external-user");
        request.setVisibility(ContextVisibility.PRIVATE.name());

        ContextMemoryCandidateResponse created = service.createCandidate(request);

        assertNotNull(created.getId());
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), created.getStatus());
        assertEquals(MemoryLane.PROJECT_DEV.name(), created.getMemoryLane());
        assertEquals(ContextVisibility.PROJECT.name(), created.getVisibility());
        assertNull(created.getUserId());
        assertEquals("codex", created.getProposedBy());

        ArgumentCaptor<ContextMemoryCandidateEntity> entityCaptor =
                ArgumentCaptor.forClass(ContextMemoryCandidateEntity.class);
        verify(candidateMapper).insert(entityCaptor.capture());
        assertEquals(MemoryLane.PROJECT_DEV.name(), entityCaptor.getValue().getMemoryLane());
        assertNull(entityCaptor.getValue().getUserId());
        assertNull(entityCaptor.getValue().getGlobalUserId());
        assertNull(entityCaptor.getValue().getExternalUserId());
        assertEquals(ContextVisibility.PROJECT.name(), entityCaptor.getValue().getVisibility());
    }

    @Test
    void createProjectDevCandidateFromAiCodingKeyAuditsAiToolActor() {
        ContextMemoryCandidateCreateRequest request = projectDevRequest(
                ContextMemoryCandidateType.API_CONTEXT.name(),
                "GET /api/orders returns tenant scoped orders");
        request.setProjectId(7L);
        request.setProposedBy("aiCodingKey:7");

        service.createCandidate(request);

        ArgumentCaptor<ContextQueryRequest> scopeCaptor = ArgumentCaptor.forClass(ContextQueryRequest.class);
        verify(auditService).recordWithMetadata(eq(ContextAuditEventType.CANDIDATE_CREATE),
                any(), any(), scopeCaptor.capture(), isNull(), isNull(), any());
        assertEquals("AI_TOOL", scopeCaptor.getValue().getActorType());
        assertEquals("aiCodingKey:7", scopeCaptor.getValue().getActorId());
    }

    @Test
    void createCandidatesCreatesMultipleCandidatesThroughOneServiceEntry() {
        ContextMemoryCandidateCreateRequest first = projectDevRequest(
                ContextMemoryCandidateType.API_CONTEXT.name(),
                "GET /orders returns the tenant scoped order list");
        ContextMemoryCandidateCreateRequest second = projectDevRequest(
                ContextMemoryCandidateType.WORKFLOW_CONTEXT.name(),
                "Order workflow reads status from GraphSpec params");

        List<ContextMemoryCandidateResponse> created = service.createCandidates(List.of(first, second));

        assertEquals(2, created.size());
        verify(candidateMapper, times(2)).insert(any(ContextMemoryCandidateEntity.class));
    }

    @Test
    void createCandidatesPrevalidatesAllRequestsBeforeInsertingAnyCandidate() {
        ContextMemoryCandidateCreateRequest first = projectDevRequest(
                ContextMemoryCandidateType.API_CONTEXT.name(),
                "GET /orders returns the tenant scoped order list");
        ContextMemoryCandidateCreateRequest second = projectDevRequest(
                ContextMemoryCandidateType.WORKFLOW_CONTEXT.name(),
                "Workflow status transition comes from graph spec");
        second.setContent(" ");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createCandidates(List.of(first, second)));

        assertTrue(ex.getMessage().contains("content is required"));
        verify(candidateMapper, never()).insert(any(ContextMemoryCandidateEntity.class));
        verify(auditService, never()).recordWithMetadata(eq(ContextAuditEventType.CANDIDATE_CREATE),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void approveProjectDevWorkflowCandidateCreatesWorkflowContextItemWithEvidenceAndBinding() {
        ContextMemoryCandidateEntity pending = storedProjectDevCandidate(50L);
        pending.setCandidateType(ContextMemoryCandidateType.WORKFLOW_CONTEXT.name());
        pending.setWorkflowId("orderAuditFlow");
        pending.setSourceRef("src/workflows/order-audit.ts");
        pending.setUserId("runtime-user");
        pending.setGlobalUserId("global-user");
        pending.setExternalUserId("external-user");
        pending.setVisibility(ContextVisibility.PRIVATE.name());
        when(candidateMapper.selectById(50L)).thenReturn(pending);
        when(namespaceService.createOrGetNamespace(any())).thenReturn(namespaceResponse(120L, ContextNamespaceType.WORKFLOW.name()));
        when(itemService.createItem(any())).thenReturn(ContextItemResponse.builder().id(700L).build());

        ContextMemoryCandidateResponse approved = service.approveCandidate(50L, projectDevReview("confirmed"));

        assertEquals(ContextMemoryCandidateStatus.APPROVED.name(), approved.getStatus());
        assertEquals(700L, approved.getApprovedItemId());

        ArgumentCaptor<ContextNamespaceRequest> nsCaptor = ArgumentCaptor.forClass(ContextNamespaceRequest.class);
        verify(namespaceService).createOrGetNamespace(nsCaptor.capture());
        assertEquals(ContextNamespaceType.WORKFLOW.name(), nsCaptor.getValue().getNamespaceType());
        assertEquals("WORKFLOW", nsCaptor.getValue().getOwnerType());
        assertEquals("orderAuditFlow", nsCaptor.getValue().getOwnerId());

        ArgumentCaptor<ContextItemCreateRequest> itemCaptor = ArgumentCaptor.forClass(ContextItemCreateRequest.class);
        verify(itemService).createItem(itemCaptor.capture());
        ContextItemCreateRequest item = itemCaptor.getValue();
        assertEquals(120L, item.getNamespaceId());
        assertEquals(MemoryLane.PROJECT_DEV.name(), item.getMemoryLane());
        assertEquals(ContextVisibility.PROJECT.name(), item.getVisibility());
        assertEquals(ContextItemType.WORKFLOW_CONTEXT.name(), item.getItemType());
        assertNull(item.getUserId());
        assertEquals("orderAuditFlow", item.getWorkflowId());
        assertEquals(1, item.getBindings().size());
        assertEquals(ContextBindType.WORKFLOW.name(), item.getBindings().get(0).getBindType());
        assertEquals("orderAuditFlow", item.getBindings().get(0).getBindId());
        assertEquals(1, item.getEvidence().size());
        assertEquals("SOURCE_FILE", item.getEvidence().get(0).getEvidenceType());
        assertEquals("src/workflows/order-audit.ts", item.getEvidence().get(0).getEvidenceRef());

        ArgumentCaptor<ContextQueryRequest> scopeCaptor = ArgumentCaptor.forClass(ContextQueryRequest.class);
        verify(auditService).recordWithMetadata(eq(ContextAuditEventType.CANDIDATE_APPROVE),
                any(), any(), scopeCaptor.capture(), eq(700L), eq(120L), any());
        assertEquals("USER", scopeCaptor.getValue().getActorType());
        assertEquals("platform-admin", scopeCaptor.getValue().getActorId());
    }

    @Test
    void approveRejectsProjectDevWorkflowCandidateWithoutWorkflowTarget() {
        ContextMemoryCandidateEntity pending = storedProjectDevCandidate(57L);
        pending.setCandidateType(ContextMemoryCandidateType.WORKFLOW_CONTEXT.name());
        pending.setWorkflowId(null);
        pending.setWorkflowKey(null);
        when(candidateMapper.selectById(57L)).thenReturn(pending);
        when(namespaceService.createOrGetNamespace(any()))
                .thenReturn(namespaceResponse(123L, ContextNamespaceType.PROJECT.name()));
        when(itemService.createItem(any())).thenReturn(ContextItemResponse.builder().id(703L).build());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(57L, projectDevReview("missing workflow target")));

        assertTrue(ex.getMessage().contains("WORKFLOW_CONTEXT candidate requires workflowId or workflowKey"));
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), pending.getStatus());
        verify(itemService, never()).createItem(any());
        verify(candidateMapper, never()).updateById(pending);
    }

    @Test
    void approveRejectsProjectDevPageCandidateWithoutPageTarget() {
        ContextMemoryCandidateEntity pending = storedProjectDevCandidate(58L);
        pending.setCandidateType(ContextMemoryCandidateType.PAGE_CONTEXT.name());
        pending.setPageInstanceId(null);
        when(candidateMapper.selectById(58L)).thenReturn(pending);
        when(namespaceService.createOrGetNamespace(any()))
                .thenReturn(namespaceResponse(124L, ContextNamespaceType.PROJECT.name()));
        when(itemService.createItem(any())).thenReturn(ContextItemResponse.builder().id(704L).build());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(58L, projectDevReview("missing page target")));

        assertTrue(ex.getMessage().contains("PAGE_CONTEXT candidate requires pageInstanceId"));
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), pending.getStatus());
        verify(itemService, never()).createItem(any());
        verify(candidateMapper, never()).updateById(pending);
    }

    @Test
    void approveRejectsProjectDevApiCandidateWithoutApiTarget() {
        ContextMemoryCandidateEntity pending = storedProjectDevCandidate(59L);
        pending.setCandidateType(ContextMemoryCandidateType.API_CONTEXT.name());
        pending.setSourceRef(null);
        when(candidateMapper.selectById(59L)).thenReturn(pending);
        when(namespaceService.createOrGetNamespace(any()))
                .thenReturn(namespaceResponse(125L, ContextNamespaceType.PROJECT.name()));
        when(itemService.createItem(any())).thenReturn(ContextItemResponse.builder().id(705L).build());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(59L, projectDevReview("missing api target")));

        assertTrue(ex.getMessage().contains("API_CONTEXT candidate requires sourceRef"));
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), pending.getStatus());
        verify(itemService, never()).createItem(any());
        verify(candidateMapper, never()).updateById(pending);
    }

    @Test
    void approveCandidatesReviewsEachPendingCandidateThroughSameStateMachine() {
        ContextMemoryCandidateEntity first = storedProjectDevCandidate(51L);
        ContextMemoryCandidateEntity second = storedProjectDevCandidate(52L);
        second.setCandidateType(ContextMemoryCandidateType.API_CONTEXT.name());
        second.setSourceRef("GET /api/orders");
        when(candidateMapper.selectById(51L)).thenReturn(first);
        when(candidateMapper.selectById(52L)).thenReturn(second);
        when(namespaceService.createOrGetNamespace(any()))
                .thenReturn(namespaceResponse(121L, ContextNamespaceType.PROJECT.name()))
                .thenReturn(namespaceResponse(122L, ContextNamespaceType.API.name()));
        when(itemService.createItem(any()))
                .thenReturn(ContextItemResponse.builder().id(701L).build())
                .thenReturn(ContextItemResponse.builder().id(702L).build());

        List<ContextMemoryCandidateResponse> approved =
                service.approveCandidates(projectDevBatchReview(List.of(51L, 52L), "confirmed batch"));

        assertEquals(2, approved.size());
        assertEquals(ContextMemoryCandidateStatus.APPROVED.name(), first.getStatus());
        assertEquals(ContextMemoryCandidateStatus.APPROVED.name(), second.getStatus());
        assertEquals(701L, first.getApprovedItemId());
        assertEquals(702L, second.getApprovedItemId());
        verify(itemService, times(2)).createItem(any());
        verify(candidateMapper).updateById(first);
        verify(candidateMapper).updateById(second);
    }

    @Test
    void approveCandidatesPrevalidatesProjectDevTargetsBeforeCreatingItems() {
        ContextMemoryCandidateEntity first = storedProjectDevCandidate(60L);
        ContextMemoryCandidateEntity second = storedProjectDevCandidate(61L);
        second.setCandidateType(ContextMemoryCandidateType.WORKFLOW_CONTEXT.name());
        second.setWorkflowId(null);
        second.setWorkflowKey(null);
        when(candidateMapper.selectById(60L)).thenReturn(first);
        when(candidateMapper.selectById(61L)).thenReturn(second);
        when(namespaceService.createOrGetNamespace(any()))
                .thenReturn(namespaceResponse(126L, ContextNamespaceType.PROJECT.name()));
        when(itemService.createItem(any())).thenReturn(ContextItemResponse.builder().id(706L).build());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidates(projectDevBatchReview(List.of(60L, 61L), "confirmed batch")));

        assertTrue(ex.getMessage().contains("WORKFLOW_CONTEXT candidate requires workflowId or workflowKey"));
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), first.getStatus());
        assertNull(first.getApprovedItemId());
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), second.getStatus());
        verify(namespaceService, never()).createOrGetNamespace(any());
        verify(itemService, never()).createItem(any());
        verify(candidateMapper, never()).updateById(any(ContextMemoryCandidateEntity.class));
    }

    @Test
    void rejectCandidatesMarksEachPendingCandidateRejectedWithoutCreatingItems() {
        ContextMemoryCandidateEntity first = storedProjectDevCandidate(53L);
        ContextMemoryCandidateEntity second = storedProjectDevCandidate(54L);
        when(candidateMapper.selectById(53L)).thenReturn(first);
        when(candidateMapper.selectById(54L)).thenReturn(second);

        List<ContextMemoryCandidateResponse> rejected =
                service.rejectCandidates(projectDevBatchReview(List.of(53L, 54L), "not useful"));

        assertEquals(2, rejected.size());
        assertEquals(ContextMemoryCandidateStatus.REJECTED.name(), first.getStatus());
        assertEquals(ContextMemoryCandidateStatus.REJECTED.name(), second.getStatus());
        verify(itemService, never()).createItem(any());
        verify(candidateMapper).updateById(first);
        verify(candidateMapper).updateById(second);
    }

    @Test
    void rejectCandidatesPrevalidatesPendingCandidatesBeforeUpdatingStatus() {
        ContextMemoryCandidateEntity first = storedProjectDevCandidate(62L);
        ContextMemoryCandidateEntity second = storedProjectDevCandidate(63L);
        second.setStatus(ContextMemoryCandidateStatus.APPROVED.name());
        when(candidateMapper.selectById(62L)).thenReturn(first);
        when(candidateMapper.selectById(63L)).thenReturn(second);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.rejectCandidates(projectDevBatchReview(List.of(62L, 63L), "not useful")));

        assertTrue(ex.getMessage().contains("Only PENDING candidates can be reviewed"));
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), first.getStatus());
        assertEquals(ContextMemoryCandidateStatus.APPROVED.name(), second.getStatus());
        verify(itemService, never()).createItem(any());
        verify(candidateMapper, never()).updateById(any(ContextMemoryCandidateEntity.class));
        verify(auditService, never()).recordWithMetadata(eq(ContextAuditEventType.CANDIDATE_REJECT),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateCandidateEditsPendingProjectDevCandidateAndAudits() {
        ContextMemoryCandidateEntity pending = storedProjectDevCandidate(55L);
        when(candidateMapper.selectById(55L)).thenReturn(pending);

        ContextMemoryCandidateUpdateRequest request = projectDevUpdate("edited by reviewer");
        request.setTitle("Order API context");
        request.setContent("GET /api/orders returns tenant scoped orders");
        request.setCandidateType(ContextMemoryCandidateType.API_CONTEXT.name());
        request.setSourceType(ContextSourceType.API.name());
        request.setSourceRef("GET /api/orders");
        request.setVisibility(ContextVisibility.GLOBAL.name());
        request.setUpdatedBy("platform-admin");

        ContextMemoryCandidateResponse updated = service.updateCandidate(55L, request);

        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), updated.getStatus());
        assertEquals("Order API context", updated.getTitle());
        assertEquals(ContextMemoryCandidateType.API_CONTEXT.name(), updated.getCandidateType());
        assertEquals(ContextSourceType.API.name(), pending.getSourceType());
        assertEquals("GET /api/orders", pending.getSourceRef());
        assertEquals(ContextVisibility.PROJECT.name(), pending.getVisibility());
        verify(candidateMapper).updateById(pending);
        ArgumentCaptor<ContextQueryRequest> scopeCaptor = ArgumentCaptor.forClass(ContextQueryRequest.class);
        verify(auditService).recordWithMetadata(eq(ContextAuditEventType.CANDIDATE_UPDATE),
                any(), eq("edited by reviewer"), scopeCaptor.capture(), isNull(), isNull(), any());
        assertEquals("USER", scopeCaptor.getValue().getActorType());
        assertEquals("platform-admin", scopeCaptor.getValue().getActorId());
    }

    @Test
    void updateCandidateRejectsReviewedCandidate() {
        ContextMemoryCandidateEntity approved = storedProjectDevCandidate(56L);
        approved.setStatus(ContextMemoryCandidateStatus.APPROVED.name());
        when(candidateMapper.selectById(56L)).thenReturn(approved);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateCandidate(56L, projectDevUpdate("too late")));
        verify(candidateMapper, never()).updateById(any());
    }

    @Test
    void approveCandidatesRejectsEmptyCandidateIds() {
        ContextMemoryCandidateBatchReviewRequest request = projectDevBatchReview(List.of(), "empty");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidates(request));

        assertTrue(ex.getMessage().contains("candidateIds is required"));
    }

    @Test
    void approveCandidatesRejectsDuplicateCandidateIdsBeforeLoadingCandidates() {
        ContextMemoryCandidateBatchReviewRequest request = projectDevBatchReview(List.of(64L, 64L), "duplicate");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidates(request));

        assertTrue(ex.getMessage().contains("candidateIds must be unique"));
        verify(candidateMapper, never()).selectById(any());
        verify(itemService, never()).createItem(any());
        verify(candidateMapper, never()).updateById(any(ContextMemoryCandidateEntity.class));
    }

    @Test
    void createCandidateReturnsExistingPendingDuplicateForSameUserTypeAndContent() {
        ContextMemoryCandidateEntity existing = storedCandidate(77L, "user-a");
        existing.setContent("用户偏好使用深色模式");
        existing.setCandidateType(ContextMemoryCandidateType.PREFERENCE.name());
        when(candidateMapper.selectList(any())).thenReturn(List.of(existing));

        ContextMemoryCandidateCreateRequest request = createRequest("user-a", " 用户偏好使用深色模式 ");
        request.setCandidateType(ContextMemoryCandidateType.PREFERENCE.name());
        ContextMemoryCandidateResponse created = service.createCandidate(request);

        assertEquals(77L, created.getId());
        assertEquals("用户偏好使用深色模式", created.getContent());
        verify(candidateMapper, never()).insert(any(ContextMemoryCandidateEntity.class));
        verify(auditService, never()).recordWithMetadata(eq(ContextAuditEventType.CANDIDATE_CREATE), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listCandidatesOnlyReturnsSameTenantAndUserPending() {
        ContextMemoryCandidateEntity userA = storedCandidate(1L, "user-a");
        when(candidateMapper.selectList(any())).thenReturn(List.of(userA));

        List<ContextMemoryCandidateResponse> hits = service.listCandidates(query("user-a"));

        assertEquals(1, hits.size());
        assertEquals("user-a", hits.get(0).getUserId());

        assertThrows(IllegalArgumentException.class, () -> service.getCandidate(1L, query("user-b")));
    }

    @Test
    void listProjectDevCandidatesFiltersByTraceIdWhenProvided() {
        ContextMemoryCandidateEntity candidate = storedProjectDevCandidate(88L);
        candidate.setTraceId("ai-coding-submission-001");
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate));

        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTenantId("default");
        query.setProjectCode("demo-project");
        query.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        query.setTraceId("ai-coding-submission-001");

        List<ContextMemoryCandidateResponse> hits = service.listCandidates(query);

        assertEquals(1, hits.size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ContextMemoryCandidateEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(candidateMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ContextMemoryCandidateEntity.class);
        assertTrue(captor.getValue().getSqlSegment().contains("trace_id"));
    }

    @Test
    void listProjectDevCandidatesCanFilterPageWorkbenchSources() {
        ContextMemoryCandidateEntity candidate = storedProjectDevCandidate(89L);
        candidate.setNamespaceId(321L);
        candidate.setCandidateType(ContextMemoryCandidateType.PAGE_CONTEXT.name());
        candidate.setSourceType(ContextSourceType.DOC.name());
        candidate.setPageInstanceId("teamArchive.list");
        candidate.setOrigin("ATTACHMENT_EXTRACT");
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate));

        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTenantId("default");
        query.setProjectCode("demo-project");
        query.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        query.setNamespaceId(321L);
        query.setCandidateType(ContextMemoryCandidateType.PAGE_CONTEXT.name());
        query.setSourceType(ContextSourceType.DOC.name());
        query.setPageInstanceId("teamArchive.list");
        query.setOrigin("ATTACHMENT_EXTRACT");

        List<ContextMemoryCandidateResponse> hits = service.listCandidates(query);

        assertEquals(1, hits.size());
        assertEquals("teamArchive.list", hits.get(0).getPageInstanceId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ContextMemoryCandidateEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(candidateMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ContextMemoryCandidateEntity.class);
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("namespace_id"));
        assertTrue(sql.contains("candidate_type"));
        assertTrue(sql.contains("source_type"));
        assertTrue(sql.contains("page_instance_id"));
        assertTrue(sql.contains("origin"));
    }

    @Test
    void expiredCandidateNotInDefaultPendingList() {
        when(candidateMapper.selectList(any())).thenReturn(List.of());

        service.listCandidates(query("user-a"));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ContextMemoryCandidateEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(candidateMapper).selectList(captor.capture());
        assertNotNull(captor.getValue());
    }

    @Test
    void rejectCandidateDoesNotCreateContextItem() {
        ContextMemoryCandidateEntity pending = storedCandidate(10L, "user-a");
        when(candidateMapper.selectById(10L)).thenReturn(pending);

        service.rejectCandidate(10L, review("user-a", "not useful"));

        verify(itemService, never()).createItem(any());
        assertEquals(ContextMemoryCandidateStatus.REJECTED.name(), pending.getStatus());
    }

    @Test
    void deleteCandidateIsLogicalDelete() {
        ContextMemoryCandidateEntity pending = storedCandidate(11L, "user-a");
        when(candidateMapper.selectById(11L)).thenReturn(pending);

        service.deleteCandidate(11L, query("user-a"));

        assertEquals(ContextMemoryCandidateStatus.DELETED.name(), pending.getStatus());
        assertNotNull(pending.getDeletedAt());
    }

    @Test
    void approveCreatesUserNamespaceThenContextItem() {
        ContextMemoryCandidateEntity pending = storedCandidate(20L, "user-a");
        when(candidateMapper.selectById(20L)).thenReturn(pending);
        when(namespaceService.createOrGetNamespace(any())).thenReturn(namespaceResponse(100L));
        when(itemService.createItem(any())).thenReturn(ContextItemResponse.builder().id(500L).build());

        ContextMemoryCandidateResponse approved = service.approveCandidate(20L, review("user-a", "confirmed"));

        assertEquals(ContextMemoryCandidateStatus.APPROVED.name(), approved.getStatus());
        assertEquals(500L, approved.getApprovedItemId());

        ArgumentCaptor<ContextNamespaceRequest> nsCaptor = ArgumentCaptor.forClass(ContextNamespaceRequest.class);
        verify(namespaceService).createOrGetNamespace(nsCaptor.capture());
        assertEquals(ContextNamespaceType.USER.name(), nsCaptor.getValue().getNamespaceType());
        assertEquals("user-a", nsCaptor.getValue().getOwnerId());

        ArgumentCaptor<ContextItemCreateRequest> itemCaptor = ArgumentCaptor.forClass(ContextItemCreateRequest.class);
        verify(itemService).createItem(itemCaptor.capture());
        assertEquals(100L, itemCaptor.getValue().getNamespaceId());
        assertEquals(MemoryLane.RUNTIME_USER.name(), itemCaptor.getValue().getMemoryLane());
        assertEquals(ContextVisibility.PRIVATE.name(), itemCaptor.getValue().getVisibility());
        assertEquals("user-a", itemCaptor.getValue().getUserId());
        assertNull(itemCaptor.getValue().getBindings());

        verify(auditService).recordWithMetadata(eq(ContextAuditEventType.CANDIDATE_APPROVE), any(), any(), any(), eq(500L), eq(100L), any());
    }

    @Test
    void approveFailsWhenCreateItemFails() {
        ContextMemoryCandidateEntity pending = storedCandidate(21L, "user-a");
        when(candidateMapper.selectById(21L)).thenReturn(pending);
        when(namespaceService.createOrGetNamespace(any())).thenReturn(namespaceResponse(100L));
        when(itemService.createItem(any())).thenThrow(new IllegalArgumentException("create failed"));

        assertThrows(IllegalArgumentException.class, () -> service.approveCandidate(21L, review("user-a", "x")));
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), pending.getStatus());
        assertNull(pending.getApprovedItemId());
    }

    @Test
    void userBCannotApproveUserACandidate() {
        ContextMemoryCandidateEntity pending = storedCandidate(22L, "user-a");
        when(candidateMapper.selectById(22L)).thenReturn(pending);

        assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(22L, review("user-b", "hack")));
    }

    @Test
    void approveRejectsNonUserNamespace() {
        ContextMemoryCandidateEntity pending = storedCandidate(30L, "user-a");
        pending.setNamespaceId(901L);
        when(candidateMapper.selectById(30L)).thenReturn(pending);
        when(namespaceService.requireActiveNamespace(901L)).thenReturn(namespaceEntity(
                901L, "default", ContextNamespaceType.PROJECT.name(), "USER", "user-a"));

        assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(30L, review("user-a", "bad type")));
        verify(itemService, never()).createItem(any());
    }

    @Test
    void approveRejectsOwnerlessNamespace() {
        ContextMemoryCandidateEntity pending = storedCandidate(31L, "user-a");
        pending.setNamespaceId(902L);
        when(candidateMapper.selectById(31L)).thenReturn(pending);
        when(namespaceService.requireActiveNamespace(902L)).thenReturn(namespaceEntity(
                902L, "default", ContextNamespaceType.USER.name(), null, null));

        assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(31L, review("user-a", "missing owner")));
        verify(itemService, never()).createItem(any());
    }

    @Test
    void approveRejectsNamespaceOwnedByAnotherUser() {
        ContextMemoryCandidateEntity pending = storedCandidate(32L, "user-a");
        pending.setNamespaceId(903L);
        when(candidateMapper.selectById(32L)).thenReturn(pending);
        when(namespaceService.requireActiveNamespace(903L)).thenReturn(namespaceEntity(
                903L, "default", ContextNamespaceType.USER.name(), "USER", "user-b"));

        assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(32L, review("user-a", "wrong owner")));
        verify(itemService, never()).createItem(any());
    }

    @Test
    void approveRejectsCrossTenantNamespace() {
        ContextMemoryCandidateEntity pending = storedCandidate(23L, "user-a");
        pending.setNamespaceId(999L);
        when(candidateMapper.selectById(23L)).thenReturn(pending);
        ContextNamespaceEntity foreign = namespaceEntity(
                999L, "tenant-b", ContextNamespaceType.USER.name(), "USER", "user-a");
        when(namespaceService.requireActiveNamespace(999L)).thenReturn(foreign);

        assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(23L, review("user-a", "bad namespace")));
        verify(itemService, never()).createItem(any());
    }

    @Test
    void approveRejectsProjectIdOnlyCandidateWhenNamespaceOnlyHasProjectCode() {
        ContextMemoryCandidateEntity pending = storedCandidate(40L, "user-a");
        pending.setProjectCode(null);
        pending.setProjectId(42L);
        pending.setNamespaceId(910L);
        when(candidateMapper.selectById(40L)).thenReturn(pending);
        ContextNamespaceEntity namespace = namespaceEntity(
                910L, "default", ContextNamespaceType.USER.name(), "USER", "user-a");
        namespace.setProjectCode("demo-project");
        namespace.setProjectId(null);
        when(namespaceService.requireActiveNamespace(910L)).thenReturn(namespace);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(40L, review("user-a", "mismatch")));
        assertTrue(ex.getMessage().contains("Namespace project mismatch for candidate approve"));
        verify(itemService, never()).createItem(any());
    }

    @Test
    void approveRejectsProjectCodeOnlyCandidateWhenNamespaceOnlyHasProjectId() {
        ContextMemoryCandidateEntity pending = storedCandidate(41L, "user-a");
        pending.setProjectCode("demo-project");
        pending.setProjectId(null);
        pending.setNamespaceId(911L);
        when(candidateMapper.selectById(41L)).thenReturn(pending);
        ContextNamespaceEntity namespace = namespaceEntity(
                911L, "default", ContextNamespaceType.USER.name(), "USER", "user-a");
        namespace.setProjectCode(null);
        namespace.setProjectId(42L);
        when(namespaceService.requireActiveNamespace(911L)).thenReturn(namespace);

        assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(41L, review("user-a", "mismatch")));
        verify(itemService, never()).createItem(any());
    }

    @Test
    void approveRejectsWhenCandidateProjectCodeMatchesButProjectIdConflicts() {
        ContextMemoryCandidateEntity pending = storedCandidate(42L, "user-a");
        pending.setProjectCode("demo-project");
        pending.setProjectId(42L);
        pending.setNamespaceId(912L);
        when(candidateMapper.selectById(42L)).thenReturn(pending);
        ContextNamespaceEntity namespace = namespaceEntity(
                912L, "default", ContextNamespaceType.USER.name(), "USER", "user-a");
        namespace.setProjectCode("demo-project");
        namespace.setProjectId(99L);
        when(namespaceService.requireActiveNamespace(912L)).thenReturn(namespace);

        assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(42L, review("user-a", "conflict")));
        verify(itemService, never()).createItem(any());
    }

    @Test
    void approveRejectsWhenCandidateProjectIdMatchesButProjectCodeConflicts() {
        ContextMemoryCandidateEntity pending = storedCandidate(43L, "user-a");
        pending.setProjectCode("demo-project");
        pending.setProjectId(42L);
        pending.setNamespaceId(913L);
        when(candidateMapper.selectById(43L)).thenReturn(pending);
        ContextNamespaceEntity namespace = namespaceEntity(
                913L, "default", ContextNamespaceType.USER.name(), "USER", "user-a");
        namespace.setProjectCode("other-project");
        namespace.setProjectId(42L);
        when(namespaceService.requireActiveNamespace(913L)).thenReturn(namespace);

        assertThrows(IllegalArgumentException.class,
                () -> service.approveCandidate(43L, review("user-a", "conflict")));
        verify(itemService, never()).createItem(any());
    }

    @Test
    void approveAllowsProjectIdOnlyCandidateWithProjectIdOnlyUserNamespace() {
        ContextMemoryCandidateEntity pending = storedCandidate(44L, "user-a");
        pending.setProjectCode(null);
        pending.setProjectId(42L);
        pending.setNamespaceId(914L);
        when(candidateMapper.selectById(44L)).thenReturn(pending);
        ContextNamespaceEntity namespace = namespaceEntity(
                914L, "default", ContextNamespaceType.USER.name(), "USER", "user-a");
        namespace.setProjectCode(null);
        namespace.setProjectId(42L);
        when(namespaceService.requireActiveNamespace(914L)).thenReturn(namespace);
        when(itemService.createItem(any())).thenReturn(ContextItemResponse.builder().id(600L).build());

        ContextMemoryCandidateResponse approved = service.approveCandidate(44L, review("user-a", "ok"));

        assertEquals(ContextMemoryCandidateStatus.APPROVED.name(), approved.getStatus());
        verify(itemService).createItem(any());
    }

    @Test
    void resolveUserIdPrefersGlobalUserId() {
        assertEquals("global-a", service.resolveUserId("global-a", "external-a", "platform-a"));
    }

    private ContextMemoryCandidateCreateRequest createRequest(String userId, String content) {
        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setTenantId("default");
        request.setProjectCode("demo-project");
        request.setCandidateType(ContextMemoryCandidateType.PREFERENCE.name());
        request.setContent(content);
        request.setSourceType(ContextSourceType.USER_MESSAGE.name());
        request.setGlobalUserId(userId);
        request.setExternalUserId(userId);
        request.setUserId(userId);
        return request;
    }

    private ContextMemoryCandidateCreateRequest projectDevRequest(String candidateType, String content) {
        ContextMemoryCandidateCreateRequest request = new ContextMemoryCandidateCreateRequest();
        request.setTenantId("default");
        request.setProjectCode("demo-project");
        request.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        request.setCandidateType(candidateType);
        request.setContent(content);
        request.setSourceType(ContextSourceType.CODE.name());
        request.setProposedBy("codex");
        return request;
    }

    private ContextMemoryCandidateQueryRequest query(String userId) {
        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTenantId("default");
        query.setUserId(userId);
        return query;
    }

    private ContextMemoryCandidateReviewRequest review(String userId, String reason) {
        ContextMemoryCandidateReviewRequest request = new ContextMemoryCandidateReviewRequest();
        request.setTenantId("default");
        request.setUserId(userId);
        request.setReviewedBy(userId);
        request.setReviewReason(reason);
        return request;
    }

    private ContextMemoryCandidateReviewRequest projectDevReview(String reason) {
        ContextMemoryCandidateReviewRequest request = new ContextMemoryCandidateReviewRequest();
        request.setTenantId("default");
        request.setProjectCode("demo-project");
        request.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        request.setReviewedBy("platform-admin");
        request.setReviewReason(reason);
        return request;
    }

    private ContextMemoryCandidateBatchReviewRequest projectDevBatchReview(List<Long> ids, String reason) {
        ContextMemoryCandidateBatchReviewRequest request = new ContextMemoryCandidateBatchReviewRequest();
        request.setTenantId("default");
        request.setProjectCode("demo-project");
        request.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        request.setReviewedBy("platform-admin");
        request.setReviewReason(reason);
        request.setCandidateIds(ids);
        return request;
    }

    private ContextMemoryCandidateUpdateRequest projectDevUpdate(String reason) {
        ContextMemoryCandidateUpdateRequest request = new ContextMemoryCandidateUpdateRequest();
        request.setTenantId("default");
        request.setProjectCode("demo-project");
        request.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        request.setUpdateReason(reason);
        return request;
    }

    private ContextNamespaceEntity namespaceEntity(Long id,
                                                   String tenantId,
                                                   String namespaceType,
                                                   String ownerType,
                                                   String ownerId) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setProjectCode("demo-project");
        entity.setNamespaceType(namespaceType);
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId);
        return entity;
    }

    private ContextNamespaceResponse namespaceResponse(Long id) {
        return ContextNamespaceResponse.builder()
                .id(id)
                .namespaceType(ContextNamespaceType.USER.name())
                .build();
    }

    private ContextNamespaceResponse namespaceResponse(Long id, String namespaceType) {
        return ContextNamespaceResponse.builder()
                .id(id)
                .namespaceType(namespaceType)
                .build();
    }

    private ContextMemoryCandidateEntity storedCandidate(Long id, String userId) {
        ContextMemoryCandidateEntity entity = new ContextMemoryCandidateEntity();
        entity.setId(id);
        entity.setCandidateKey("ctx-candidate-" + id);
        entity.setTenantId("default");
        entity.setProjectCode("demo-project");
        entity.setMemoryLane(MemoryLane.RUNTIME_USER.name());
        entity.setCandidateType(ContextMemoryCandidateType.PREFERENCE.name());
        entity.setContent("请记住我喜欢深色模式");
        entity.setSourceType(ContextSourceType.USER_MESSAGE.name());
        entity.setUserId(userId);
        entity.setGlobalUserId(userId);
        entity.setVisibility(ContextVisibility.PRIVATE.name());
        entity.setStatus(ContextMemoryCandidateStatus.PENDING.name());
        entity.setConfidence(new BigDecimal("0.7000"));
        entity.setTrustLevel("LOW");
        entity.setExpiresAt(LocalDateTime.now().plusDays(7));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextMemoryCandidateEntity storedProjectDevCandidate(Long id) {
        ContextMemoryCandidateEntity entity = storedCandidate(id, null);
        entity.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        entity.setCandidateType(ContextMemoryCandidateType.NOTE.name());
        entity.setContent("Workflow input comes from the order detail route state");
        entity.setSourceType(ContextSourceType.CODE.name());
        entity.setVisibility(ContextVisibility.PROJECT.name());
        entity.setUserId(null);
        entity.setGlobalUserId(null);
        entity.setExternalUserId(null);
        entity.setProposedBy("codex");
        return entity;
    }
}
