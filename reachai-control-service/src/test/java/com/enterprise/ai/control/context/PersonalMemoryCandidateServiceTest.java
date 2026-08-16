package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.context.PersonalMemoryCandidateService.ObservationCommand;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PersonalMemoryCandidateServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "candidate"),
                ContextMemoryCandidateEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "candidate-item"),
                ContextItemEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "candidate-audit"),
                ContextAuditEventEntity.class);
    }

    @Test
    void passivePreferenceCreatesPrivatePendingCandidate() {
        Fixture fixture = fixture();
        ContextNamespaceEntity namespace = namespace();
        when(fixture.memoryService.ensureNamespace(any())).thenReturn(namespace);
        when(fixture.itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(fixture.candidateMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            ContextMemoryCandidateEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            return 1;
        }).when(fixture.candidateMapper).insert(any());

        var result = fixture.service.observe(principal(), new ObservationCommand(
                "我希望以后都用中文回答", "好的", "s1", "t1", "a1", "AGENT", true));

        assertEquals(1, result.created());
        ArgumentCaptor<ContextMemoryCandidateEntity> saved = ArgumentCaptor.forClass(ContextMemoryCandidateEntity.class);
        verify(fixture.candidateMapper).insert(saved.capture());
        assertEquals("PENDING", saved.getValue().getStatus());
        assertEquals("PRIVATE", saved.getValue().getVisibility());
        assertEquals("response-language", saved.getValue().getSemanticKey());
        assertNotNull(saved.getValue().getDedupeKey());
        long ttlHours = java.time.Duration.between(LocalDateTime.now(), saved.getValue().getExpiresAt()).toHours();
        org.junit.jupiter.api.Assertions.assertTrue(ttlHours >= 719 && ttlHours <= 720);
    }

    @Test
    void explicitRememberUsesCanonicalServiceWithoutCandidate() {
        Fixture fixture = fixture();
        when(fixture.memoryService.remember(any(), any())).thenReturn(null);

        var result = fixture.service.observe(principal(), new ObservationCommand(
                "请记住我住在青岛", "好的", "s1", "t1", "a1", "AGENT", true));

        assertEquals(1, result.remembered());
        verify(fixture.memoryService).remember(any(), any());
        verify(fixture.candidateMapper, never()).insert(any());
    }

    @Test
    void contributeOptOutSkipsExtraction() {
        Fixture fixture = fixture();

        var result = fixture.service.observe(principal(), new ObservationCommand(
                "我住在青岛", null, "s1", "t1", "a1", "AGENT", false));

        assertEquals(0, result.created());
        verify(fixture.memoryService, never()).ensureNamespace(any());
    }

    @Test
    void passiveCandidateQuotaSkipsSafelyAndWritesAudit() {
        Fixture fixture = fixture(1);
        ContextNamespaceEntity namespace = namespace();
        when(fixture.memoryService.ensureNamespace(any())).thenReturn(namespace);
        when(fixture.itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(fixture.candidateMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(fixture.candidateMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        var result = fixture.service.observe(principal(), new ObservationCommand(
                "我希望以后都用中文回答", "好的", "s1", "t1", "a1", "AGENT", true));

        assertEquals(0, result.created());
        assertEquals(1, result.quotaSkipped());
        verify(fixture.memoryService).lockNamespace(namespace);
        verify(fixture.candidateMapper, never()).insert(any());
        ArgumentCaptor<ContextAuditEventEntity> audit =
                ArgumentCaptor.forClass(ContextAuditEventEntity.class);
        verify(fixture.auditMapper).insert(audit.capture());
        assertEquals("SKIP", audit.getValue().getDecision());
        assertEquals("OWNER_PENDING_CANDIDATE_QUOTA", audit.getValue().getReason());
    }

    @Test
    void reviewLocksTheCandidateBeforeRejectingIt() {
        Fixture fixture = fixture();
        ContextMemoryCandidateEntity candidate = new ContextMemoryCandidateEntity();
        candidate.setId(12L);
        candidate.setCandidateKey("candidate-12");
        candidate.setTenantId("default");
        candidate.setUserId("user-1");
        candidate.setMemoryLane("RUNTIME_USER");
        candidate.setVisibility("PRIVATE");
        candidate.setStatus("PENDING");
        when(fixture.candidateMapper.selectByIdForUpdate(12L)).thenReturn(candidate);

        var result = fixture.service.reject(principal(), 12L,
                new PersonalMemoryCandidateService.ReviewCommand(null, null, null, null, "不需要"));

        assertEquals("REJECTED", result.status());
        verify(fixture.candidateMapper).selectByIdForUpdate(12L);
        verify(fixture.candidateMapper).updateById(candidate);
    }

    private static Fixture fixture() {
        return fixture(500);
    }

    private static Fixture fixture(int maxPendingCandidatesPerOwner) {
        ContextMemoryCandidateMapper candidateMapper = mock(ContextMemoryCandidateMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextAuditEventMapper auditMapper = mock(ContextAuditEventMapper.class);
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        PersonalMemoryCandidateService service = new PersonalMemoryCandidateService(
                candidateMapper, itemMapper, auditMapper, memoryService,
                new PersonalMemoryCandidateExtractor(), new ObjectMapper(),
                maxPendingCandidatesPerOwner);
        return new Fixture(service, candidateMapper, itemMapper, auditMapper, memoryService);
    }

    private static PersonalMemoryPrincipal principal() {
        return new PersonalMemoryPrincipal("default", "user-1", 1L, "user", "session");
    }

    private static ContextNamespaceEntity namespace() {
        ContextNamespaceEntity namespace = new ContextNamespaceEntity();
        namespace.setId(3L);
        namespace.setNamespaceKey("ctx:user:test");
        namespace.setNamespaceType("USER");
        namespace.setOwnerType("RUNTIME_USER");
        namespace.setOwnerId("user-1");
        namespace.setTenantId("default");
        namespace.setStatus("ACTIVE");
        return namespace;
    }

    private record Fixture(PersonalMemoryCandidateService service,
                           ContextMemoryCandidateMapper candidateMapper,
                           ContextItemMapper itemMapper,
                           ContextAuditEventMapper auditMapper,
                           PersonalMemoryService memoryService) {
    }
}
