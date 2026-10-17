package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateService.ObservationCommand;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
                PersonalMemoryCandidateEntity.class);
    }

    @Test
    void passivePreferenceCreatesPendingCandidate() {
        Fixture fixture = fixture(500);
        when(fixture.candidateMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            PersonalMemoryCandidateEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            return 1;
        }).when(fixture.candidateMapper).insert(any(PersonalMemoryCandidateEntity.class));

        var result = fixture.service.observe(principal(), new ObservationCommand(
                "我希望以后都用中文回答", "好的", "s1", "t1", "a1"));

        assertEquals(1, result.created());
        ArgumentCaptor<PersonalMemoryCandidateEntity> saved = ArgumentCaptor.forClass(PersonalMemoryCandidateEntity.class);
        verify(fixture.candidateMapper).insert(saved.capture());
        assertEquals("PENDING", saved.getValue().getStatus());
        assertEquals("user-1", saved.getValue().getOwnerId());
        assertEquals("a1", saved.getValue().getSourceAgentId());
        assertEquals("response-language", saved.getValue().getSemanticKey());
        assertNotNull(saved.getValue().getDedupeKey());
        long ttlHours = java.time.Duration.between(LocalDateTime.now(), saved.getValue().getExpiresAt()).toHours();
        assertTrue(ttlHours >= 719 && ttlHours <= 720);
    }

    @Test
    void explicitRememberUsesCanonicalServiceWithoutCandidate() {
        Fixture fixture = fixture(500);

        var result = fixture.service.observe(principal(), new ObservationCommand(
                "请记住我住在青岛", "好的", "s1", "t1", "a1"));

        assertEquals(1, result.remembered());
        verify(fixture.memoryService).remember(any(), any());
        verify(fixture.candidateMapper, never()).insert(any(PersonalMemoryCandidateEntity.class));
    }

    @Test
    void passiveCandidateQuotaSkipsSafelyAndWritesAudit() {
        Fixture fixture = fixture(1);
        when(fixture.candidateMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(fixture.candidateMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        var result = fixture.service.observe(principal(), new ObservationCommand(
                "我希望以后都用中文回答", "好的", "s1", "t1", "a1"));

        assertEquals(0, result.created());
        assertEquals(1, result.quotaSkipped());
        verify(fixture.candidateMapper, never()).insert(any(PersonalMemoryCandidateEntity.class));
        verify(fixture.memoryService).audit(eq("CANDIDATE_CREATE"), any(), isNull(), eq("a1"), eq("s1"),
                eq("t1"), eq("SKIP"), eq("OWNER_PENDING_CANDIDATE_QUOTA"), any());
    }

    @Test
    void reviewLocksTheCandidateBeforeRejectingIt() {
        Fixture fixture = fixture(500);
        PersonalMemoryCandidateEntity candidate = new PersonalMemoryCandidateEntity();
        candidate.setId(12L);
        candidate.setCandidateKey("candidate-12");
        candidate.setTenantId("default");
        candidate.setOwnerId("user-1");
        candidate.setStatus("PENDING");
        when(fixture.candidateMapper.selectByIdForUpdate(12L)).thenReturn(candidate);

        var result = fixture.service.reject(principal(), 12L);

        assertEquals("REJECTED", result.status());
        verify(fixture.candidateMapper).selectByIdForUpdate(12L);
        verify(fixture.candidateMapper).updateById(candidate);
    }

    private static Fixture fixture(int maxPendingCandidatesPerOwner) {
        PersonalMemoryCandidateMapper candidateMapper = mock(PersonalMemoryCandidateMapper.class);
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        PersonalMemoryCandidateService service = new PersonalMemoryCandidateService(
                candidateMapper, memoryService, new PersonalMemoryCandidateExtractor(),
                maxPendingCandidatesPerOwner);
        return new Fixture(service, candidateMapper, memoryService);
    }

    private static PersonalMemoryPrincipal principal() {
        return new PersonalMemoryPrincipal("default", "user-1", 1L, "user", "session");
    }

    private record Fixture(PersonalMemoryCandidateService service,
                           PersonalMemoryCandidateMapper candidateMapper,
                           PersonalMemoryService memoryService) {
    }
}
