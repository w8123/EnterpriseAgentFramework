package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkLookup.LinkedOwner;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryHit;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PersonalMemorySharedQueryServiceTest {
    private final PersonalMemoryService memories = mock(PersonalMemoryService.class);
    private final PersonalMemoryIdentityLinkLookup links = mock(PersonalMemoryIdentityLinkLookup.class);
    private final PersonalMemorySharedQueryService service = new PersonalMemorySharedQueryService(memories, links);
    private final PersonalMemoryPrincipal own = PersonalMemoryPrincipal.business("tenant", "Alice", "erp");
    private final PersonalMemoryPrincipal peer = PersonalMemoryPrincipal.business("tenant", "bob", "crm");
    private final LinkedOwner link = new LinkedOwner(9, 12, peer);

    @Test void sharesOnlyPersonalLayerAndKeepsCurrentProjectForOwner() {
        when(links.linkedOwners(own)).thenReturn(List.of(link));
        when(memories.query(eq(own), any())).thenReturn(result(hit(1, "自有项目", 3, "erp")));
        when(memories.query(eq(peer), any())).thenReturn(result(hit(2, "共享偏好", 5, null),
                hit(3, "对方项目", 9, "crm")));
        var result = service.query(own, new QueryCommand("偏好", null, 8, 6000, null, null, null, "erp"));
        assertEquals(List.of(2L, 1L), result.hits().stream().map(h -> h.hit().id()).toList());
        assertEquals(peer, result.hits().get(0).owner());
        verify(memories).query(eq(peer), argThat(q -> q.projectCode() == null));
        verify(memories).query(eq(own), argThat(q -> "erp".equals(q.projectCode())));
    }

    @Test void revokedLinkIsRecheckedBeforeAnyPeerContentIsReturned() {
        when(links.linkedOwners(own)).thenReturn(List.of(link), List.of());
        when(memories.query(eq(own), any())).thenReturn(result(hit(1, "自有", 1, null)));
        when(memories.query(eq(peer), any())).thenReturn(result(hit(2, "撤销后不返回", 3, null)));
        var result = service.query(own, new QueryCommand("", null, 8, 6000, null, null, null, null));
        assertEquals(List.of(1L), result.hits().stream().map(h -> h.hit().id()).toList());
    }

    @Test void failedLinkRevalidationKeepsOwnMemoryButNeverReturnsUnverifiedPeerData() {
        when(links.linkedOwners(own)).thenReturn(List.of(link)).thenThrow(new IllegalStateException("unavailable"));
        when(memories.query(eq(own), any())).thenReturn(result(hit(1, "自有", 1, null)));
        when(memories.query(eq(peer), any())).thenReturn(result(hit(2, "不能确认", 3, null)));
        var result = service.query(own, new QueryCommand("", null, 8, 6000, null, null, null, null));
        assertEquals(List.of(1L), result.hits().stream().map(h -> h.hit().id()).toList());
        assertTrue(result.sharingUnavailable());
    }

    @Test void mergedResultsRespectGlobalLimitAndLegacyNeverLooksUpLinks() {
        when(links.linkedOwners(own)).thenReturn(List.of(link));
        when(memories.query(eq(own), any())).thenReturn(result(hit(1, "a".repeat(200), 2, null)));
        when(memories.query(eq(peer), any())).thenReturn(result(hit(2, "b".repeat(100), 1, null)));
        var result = service.query(own, new QueryCommand("", null, 8, 256, null, null, null, null));
        assertEquals(1, result.hits().size());
        assertEquals(200, result.usedChars());
        assertTrue(result.truncated());
        reset(links);
        var legacy = new PersonalMemoryPrincipal("tenant", "Alice", null, null, null);
        when(memories.query(eq(legacy), any())).thenReturn(result(hit(4, "legacy", 1, null)));
        assertEquals(1, service.query(legacy, new QueryCommand("", null, 8, 256, null, null, null, null)).hits().size());
        verifyNoInteractions(links);
    }

    private static QueryHit hit(long id, String content, double score, String project) {
        return new QueryHit(id, "NOTE", null, content, score, null, project);
    }
    private static QueryResult result(QueryHit... hits) {
        return new QueryResult(List.of(hits), hits.length, 0, 6000, false);
    }
}
