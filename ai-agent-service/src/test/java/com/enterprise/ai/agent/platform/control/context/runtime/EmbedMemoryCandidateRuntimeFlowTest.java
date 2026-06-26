package com.enterprise.ai.agent.platform.control.context.runtime;

import com.enterprise.ai.agent.platform.control.context.*;
import com.enterprise.ai.agent.platform.control.context.memory.*;
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

/**
 * End-to-end flow: embed runtime candidate approve -> context_item -> CENTRAL runtime inject hits same userId.
 */
class EmbedMemoryCandidateRuntimeFlowTest {

    private ContextMemoryCandidateMapper candidateMapper;
    private ContextItemService itemService;
    private ContextNamespaceService namespaceService;
    private ContextAuditService auditService;
    private ContextMemoryCandidateService candidateService;
    private RuntimeUserIdentityResolver identityResolver;
    private ContextComposerService composerService;
    private RuntimeContextPackageService runtimeContextPackageService;
    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        candidateMapper = mock(ContextMemoryCandidateMapper.class);
        itemService = mock(ContextItemService.class);
        namespaceService = mock(ContextNamespaceService.class);
        auditService = mock(ContextAuditService.class);
        candidateService = new ContextMemoryCandidateService(
                candidateMapper, itemService, namespaceService, auditService, new ObjectMapper());
        identityResolver = new RuntimeUserIdentityResolver(candidateService);

        composerService = mock(ContextComposerService.class);
        RuntimeContextProperties properties = new RuntimeContextProperties();
        properties.setEnabled(true);
        runtimeContextPackageService = new RuntimeContextPackageService(
                composerService,
                new RuntimeContextPromptFormatter(properties),
                properties);

        when(candidateMapper.insert(any(ContextMemoryCandidateEntity.class))).thenAnswer(inv -> {
            ContextMemoryCandidateEntity entity = inv.getArgument(0);
            entity.setId(idSeq.getAndIncrement());
            return 1;
        });
        when(auditService.recordWithMetadata(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ContextAuditEventEntity());
    }

    @Test
    void approveCandidateThenCentralRuntimeInjectUsesSameRuntimeUserId() {
        ContextMemoryCandidateCreateRequest createRequest = new ContextMemoryCandidateCreateRequest();
        createRequest.setTenantId("default");
        createRequest.setProjectCode("demo");
        createRequest.setCandidateType(ContextMemoryCandidateType.PREFERENCE.name());
        createRequest.setContent("请记住我喜欢深色模式");
        createRequest.setSourceType(ContextSourceType.USER_MESSAGE.name());
        createRequest.setGlobalUserId("global-a");
        createRequest.setExternalUserId("external-a");
        ContextMemoryCandidateResponse created = candidateService.createCandidate(createRequest);
        assertEquals(ContextMemoryCandidateStatus.PENDING.name(), created.getStatus());

        ContextMemoryCandidateEntity pending = storedPending(created.getId(), "global-a");
        when(candidateMapper.selectById(created.getId())).thenReturn(pending);
        when(namespaceService.createOrGetNamespace(any())).thenReturn(ContextNamespaceResponse.builder().id(100L).build());
        when(itemService.createItem(any())).thenReturn(ContextItemResponse.builder().id(500L).build());

        ContextMemoryCandidateReviewRequest review = new ContextMemoryCandidateReviewRequest();
        review.setTenantId("default");
        review.setUserId("global-a");
        review.setReviewedBy("global-a");
        review.setReviewReason("confirmed");
        candidateService.approveCandidate(created.getId(), review);

        when(composerService.compose(any())).thenReturn(samplePackage("global-a"));
        RuntimeContextIdentity identity = RuntimeContextIdentity.builder()
                .tenantId("default")
                .projectCode("demo")
                .globalUserId("global-a")
                .externalUserId("external-a")
                .sessionId("sess-1")
                .runtimePlacement("CENTRAL")
                .build();
        RuntimeContextInjectionResult injection = runtimeContextPackageService.inject(identity);

        assertTrue(injection.isEnabled());
        ArgumentCaptor<ContextPackageComposeRequest> captor = ArgumentCaptor.forClass(ContextPackageComposeRequest.class);
        verify(composerService).compose(captor.capture());
        assertEquals("global-a", captor.getValue().getQuery().getUserId());
        assertEquals(MemoryLane.RUNTIME_USER.name(), captor.getValue().getQuery().getMemoryLane());
    }

    @Test
    void runtimeIdentityResolverMatchesCandidateUserId() {
        com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity session = new com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity();
        session.setSessionId("sess-1");
        session.setTenantId("default");
        session.setProjectCode("demo");
        session.setGlobalUserId("global-a");
        session.setExternalUserId("external-a");
        com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims claims = new com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims();
        claims.setGlobalUserId("global-a");
        claims.setExternalUserId("external-a");

        RuntimeUserIdentity identity = identityResolver.resolve(session, claims);
        assertEquals("global-a", identity.userId());

        ContextMemoryCandidateQueryRequest query = new ContextMemoryCandidateQueryRequest();
        query.setTenantId(identity.tenantId());
        query.setUserId(identity.userId());
        query.setProjectCode(identity.projectCode());
        assertEquals("global-a", query.getUserId());
    }

    private ContextMemoryCandidateEntity storedPending(Long id, String userId) {
        ContextMemoryCandidateEntity entity = new ContextMemoryCandidateEntity();
        entity.setId(id);
        entity.setCandidateKey("ctx-candidate-" + id);
        entity.setTenantId("default");
        entity.setProjectCode("demo");
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

    private ContextPackageResponse samplePackage(String userId) {
        ContextItemResponse item = ContextItemResponse.builder()
                .id(500L)
                .itemType(ContextItemType.PREFERENCE.name())
                .memoryLane(MemoryLane.RUNTIME_USER.name())
                .title("Preference")
                .content("请记住我喜欢深色模式")
                .confidence(new BigDecimal("0.9000"))
                .trustLevel("HIGH")
                .build();
        return ContextPackageResponse.builder()
                .memoryLane(MemoryLane.RUNTIME_USER.name())
                .tenantId("default")
                .totalItems(1)
                .truncatedCount(0)
                .projectMemory(List.of())
                .userMemory(List.of(ContextSearchResult.builder().item(item).rankScore(1.0).hitReason("binding").build()))
                .pageContext(List.of())
                .workflowContext(List.of())
                .apiContext(List.of())
                .rules(List.of())
                .evidenceSummary(List.of())
                .build();
    }
}
