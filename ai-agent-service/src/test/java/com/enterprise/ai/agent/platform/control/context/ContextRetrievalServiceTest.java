package com.enterprise.ai.agent.platform.control.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextRetrievalServiceTest {

    private ContextItemMapper itemMapper;
    private ContextNamespaceMapper namespaceMapper;
    private ContextBindingMapper bindingMapper;
    private ContextAuditEventMapper auditEventMapper;
    private ContextBindingService bindingService;
    private ContextAccessPolicyService accessPolicyService;
    private ContextAuditService auditService;
    private ContextRetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        itemMapper = mock(ContextItemMapper.class);
        namespaceMapper = mock(ContextNamespaceMapper.class);
        bindingMapper = mock(ContextBindingMapper.class);
        auditEventMapper = mock(ContextAuditEventMapper.class);
        accessPolicyService = new ContextAccessPolicyService(
                mock(com.enterprise.ai.agent.platform.control.auth.PlatformAuthorizationService.class),
                bindingMapper, itemMapper, namespaceMapper);
        auditService = new ContextAuditService(auditEventMapper);
        bindingService = new ContextBindingService(bindingMapper, accessPolicyService);
        retrievalService = new ContextRetrievalService(
                itemMapper, namespaceMapper, bindingService, accessPolicyService, auditService);
        when(auditEventMapper.insert(any(ContextAuditEventEntity.class))).thenReturn(1);
        when(bindingMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void retrievalRanksTitleMatchAboveContentMatch() {
        stubNamespace();
        ContextItemEntity titleHit = item(1L, "orders keyword in title", "orders body", MemoryLane.PROJECT_DEV);
        titleHit.setTrustLevel(ContextTrustLevel.MEDIUM.name());
        ContextItemEntity contentHit = item(2L, "other", "orders keyword in content only", MemoryLane.PROJECT_DEV);
        contentHit.setTrustLevel(ContextTrustLevel.MEDIUM.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(contentHit, titleHit));

        ContextQueryRequest query = devQuery("orders keyword");
        List<ContextSearchResult> hits = retrievalService.search(query);

        assertEquals(2, hits.size());
        assertEquals(1L, hits.get(0).getItem().getId());
        assertTrue(hits.get(0).getRankScore() > hits.get(1).getRankScore());
        assertTrue(hits.get(0).getHitReason().contains("title keyword match"));
        assertNotNull(hits.get(0).getScoreBreakdown());
    }

    @Test
    void retrievalBoostsVerifiedHighConfidenceItems() {
        stubNamespace();
        ContextItemEntity verified = item(10L, "alpha", "alpha content", MemoryLane.PROJECT_DEV);
        verified.setTrustLevel(ContextTrustLevel.VERIFIED.name());
        verified.setConfidence(new BigDecimal("0.9500"));
        verified.setLastVerifiedAt(LocalDateTime.now().minusDays(2));
        ContextItemEntity low = item(11L, "beta", "beta content", MemoryLane.PROJECT_DEV);
        low.setTrustLevel(ContextTrustLevel.LOW.name());
        low.setConfidence(new BigDecimal("0.5000"));
        when(itemMapper.selectList(any())).thenReturn(List.of(low, verified));

        List<ContextSearchResult> hits = retrievalService.search(devQuery(null));

        assertEquals(2, hits.size());
        assertEquals(10L, hits.get(0).getItem().getId());
        assertTrue(hits.get(0).getRankScore() > hits.get(1).getRankScore());
        assertTrue(hits.get(0).getScoreBreakdown().contains("trust="));
    }

    @Test
    void retrievalUsesUpdatedAtAsTieBreaker() {
        stubNamespace();
        ContextItemEntity older = item(20L, "same", "same content", MemoryLane.PROJECT_DEV);
        older.setUpdatedAt(LocalDateTime.now().minusDays(10));
        older.setTrustLevel(ContextTrustLevel.MEDIUM.name());
        older.setConfidence(new BigDecimal("0.7000"));
        ContextItemEntity newer = item(21L, "same2", "same content", MemoryLane.PROJECT_DEV);
        newer.setUpdatedAt(LocalDateTime.now().minusDays(1));
        newer.setTrustLevel(ContextTrustLevel.MEDIUM.name());
        newer.setConfidence(new BigDecimal("0.7000"));
        when(itemMapper.selectList(any())).thenReturn(List.of(older, newer));

        List<ContextSearchResult> hits = retrievalService.search(devQuery(null));

        assertEquals(21L, hits.get(0).getItem().getId());
    }

    @Test
    void retrievalDoesNotReturnExpiredOrFutureEffectiveItems() {
        stubNamespace();
        ContextItemEntity expired = item(30L, "expired", "expired", MemoryLane.PROJECT_DEV);
        expired.setExpiresAt(LocalDateTime.now().minusHours(1));
        ContextItemEntity future = item(31L, "future", "future", MemoryLane.PROJECT_DEV);
        future.setEffectiveFrom(LocalDateTime.now().plusDays(1));
        when(itemMapper.selectList(any())).thenReturn(List.of(expired, future));

        List<ContextSearchResult> hits = retrievalService.search(devQuery("expired"));

        assertTrue(hits.isEmpty());
    }

    @Test
    void retrievalDoesNotLeakRuntimeUserPrivateAcrossUsers() {
        stubNamespace();
        ContextItemEntity privateItem = item(40L, "private pref", "dark mode", MemoryLane.RUNTIME_USER);
        privateItem.setVisibility(ContextVisibility.PRIVATE.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(privateItem));
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding(40L, ContextBindType.USER, "user-a")));

        ContextQueryRequest userB = runtimeQuery("user-b");
        assertTrue(retrievalService.search(userB).isEmpty());

        ContextQueryRequest userA = runtimeQuery("user-a");
        assertEquals(1, retrievalService.search(userA).size());
    }

    @Test
    void retrievalTopKAppliedAfterScoring() {
        stubNamespace();
        when(itemMapper.selectList(any())).thenReturn(List.of(
                item(50L, "a", "content a", MemoryLane.PROJECT_DEV),
                item(51L, "b", "content b", MemoryLane.PROJECT_DEV),
                item(52L, "c", "content c", MemoryLane.PROJECT_DEV)));
        ContextQueryRequest query = devQuery(null);
        query.setTopK(2);

        List<ContextSearchResult> hits = retrievalService.search(query);

        assertEquals(2, hits.size());
    }

    @Test
    void defaultKeywordRetrievalKeepsExactPhraseMatching() {
        stubNamespace();
        ContextItemEntity preference = item(60L, "视觉偏好", "用户偏好使用深色模式", MemoryLane.RUNTIME_USER);
        preference.setVisibility(ContextVisibility.PRIVATE.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(preference));
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding(60L, ContextBindType.USER, "user-a")));

        ContextQueryRequest query = runtimeQuery("user-a");
        query.setQuery("深色 模式");

        List<ContextSearchResult> hits = retrievalService.search(query);

        assertTrue(hits.isEmpty());
    }

    @Test
    void hybridRetrievalUsesTokenFallbackWhenExactPhraseMisses() {
        stubNamespace();
        ContextItemEntity preference = item(61L, "视觉偏好", "用户偏好使用深色模式", MemoryLane.RUNTIME_USER);
        preference.setVisibility(ContextVisibility.PRIVATE.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(preference));
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding(61L, ContextBindType.USER, "user-a")));

        ContextQueryRequest query = runtimeQuery("user-a");
        query.setQuery("深色 模式");
        query.setRetrievalMode("HYBRID");

        List<ContextSearchResult> hits = retrievalService.search(query);

        assertEquals(1, hits.size());
        assertEquals(61L, hits.get(0).getItem().getId());
        assertTrue(hits.get(0).getHitReason().contains("token keyword match"));
        assertTrue(hits.get(0).getScoreBreakdown().contains("tokens="));
    }

    @Test
    void hybridRetrievalUsesCjkTokenFallbackForNaturalChineseSentence() {
        stubNamespace();
        ContextItemEntity preference = item(62L, "视觉偏好", "用户偏好使用深色模式", MemoryLane.RUNTIME_USER);
        preference.setVisibility(ContextVisibility.PRIVATE.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(preference));
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding(62L, ContextBindType.USER, "user-a")));

        ContextQueryRequest query = runtimeQuery("user-a");
        query.setQuery("我想切换成深色主题");
        query.setRetrievalMode("HYBRID");

        List<ContextSearchResult> hits = retrievalService.search(query);

        assertEquals(1, hits.size());
        assertEquals(62L, hits.get(0).getItem().getId());
        assertTrue(hits.get(0).getHitReason().contains("token keyword match"));
    }

    private void stubNamespace() {
        ContextNamespaceEntity ns = namespace();
        when(namespaceMapper.selectList(any())).thenReturn(List.of(ns));
    }

    private ContextQueryRequest devQuery(String keyword) {
        ContextQueryRequest query = new ContextQueryRequest();
        query.setTenantId("tenant-a");
        query.setProjectCode("demo-project");
        query.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        query.setQuery(keyword);
        return query;
    }

    private ContextQueryRequest runtimeQuery(String userId) {
        ContextQueryRequest query = devQuery(null);
        query.setMemoryLane(MemoryLane.RUNTIME_USER.name());
        query.setUserId(userId);
        return query;
    }

    private ContextNamespaceEntity namespace() {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setId(1L);
        entity.setTenantId("tenant-a");
        entity.setProjectCode("demo-project");
        entity.setNamespaceType(ContextNamespaceType.PROJECT.name());
        entity.setStatus(ContextStatus.ACTIVE.name());
        return entity;
    }

    private ContextItemEntity item(Long id, String title, String content, MemoryLane lane) {
        ContextItemEntity entity = new ContextItemEntity();
        entity.setId(id);
        entity.setNamespaceId(1L);
        entity.setItemKey("ctx-item-" + id);
        entity.setItemType(ContextItemType.FACT.name());
        entity.setMemoryLane(lane.name());
        entity.setTitle(title);
        entity.setContent(content);
        entity.setSummary(title);
        entity.setSourceType(ContextSourceType.MANUAL.name());
        entity.setConfidence(new BigDecimal("0.7000"));
        entity.setTrustLevel(ContextTrustLevel.MEDIUM.name());
        entity.setVisibility(ContextVisibility.PROJECT.name());
        entity.setStatus(ContextStatus.ACTIVE.name());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextBindingEntity binding(Long itemId, ContextBindType type, String bindId) {
        ContextBindingEntity entity = new ContextBindingEntity();
        entity.setItemId(itemId);
        entity.setBindType(type.name());
        entity.setBindId(bindId);
        entity.setStatus(ContextStatus.ACTIVE.name());
        return entity;
    }
}
