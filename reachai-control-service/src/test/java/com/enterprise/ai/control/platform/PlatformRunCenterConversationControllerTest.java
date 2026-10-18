package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.platform.PlatformConversationViews.ConversationRunView;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformRunCenterConversationControllerTest {

    private final LocalDateTime t0 = LocalDateTime.of(2026, 10, 17, 10, 0, 0);
    private final PlatformRequestAuthorization authorization = mock(PlatformRequestAuthorization.class);
    private final PlatformEmbedSessionMapper sessions = mock(PlatformEmbedSessionMapper.class);
    private final PlatformEmbedChatEventMapper events = mock(PlatformEmbedChatEventMapper.class);
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final PlatformAuthenticatedSession operator = mock(PlatformAuthenticatedSession.class);
    private final PlatformRunCenterConversationController controller =
            new PlatformRunCenterConversationController(authorization, sessions, events, runtime);

    @Test
    void turnsLinkAnswersByTraceAndFailedQuestionsByTime() {
        var turns = PlatformConversationTurnAssembler.assemble(
                List.of(
                        event("user", "库存多少？", null, t0),
                        event("assistant", "还有 12 台", "trace-1", t0.plusSeconds(3)),
                        event("user", "那供应商呢？", null, t0.plusMinutes(1))),
                List.of(
                        run("trace-1", "COMPLETED", t0.plusSeconds(1)),
                        run("trace-2", "FAILED", t0.plusMinutes(1).plusSeconds(1))));

        assertEquals(2, turns.size());
        assertEquals("还有 12 台", turns.get(0).answer());
        assertEquals("COMPLETED", turns.get(0).run().status());
        assertNull(turns.get(1).answer());
        assertEquals("trace-2", turns.get(1).run().traceId());
        assertEquals("FAILED", turns.get(1).run().status());
    }

    @Test
    void runsWithoutMessagesStillShowUpAsTurns() {
        var turns = PlatformConversationTurnAssembler.assemble(
                List.of(event("user", "你好", null, t0.plusMinutes(5))),
                List.of(run("early", "FAILED", t0)));

        assertEquals(2, turns.size());
        assertEquals("early", turns.get(0).run().traceId());
        assertNull(turns.get(0).question());
        assertNull(turns.get(1).run());
    }

    @Test
    void listHidesOtherProjectsEmptySessionsAndKeepsOnlyProblemsWhenAsked() {
        when(authorization.requirePermission(request, "runops:read")).thenReturn(operator);
        when(operator.hasResourcePermission(eq("runops:read"), eq("PROJECT"), any(), eq("yudao-erp"))).thenReturn(true);
        when(sessions.selectList(any())).thenReturn(List.of(
                session("s-failed", "yudao-erp"), session("s-ok", "yudao-erp"),
                session("s-empty", "yudao-erp"), session("s-other", "secret")));
        when(events.selectConversationStats(any())).thenReturn(List.of(
                stats("s-failed", 2), stats("s-ok", 1), stats("s-empty", 0)));
        when(runtime.runOpsSessionStats(any(), anyInt())).thenReturn(ResponseEntity.ok(List.of(
                Map.of("sessionId", "s-failed", "runCount", 2, "failedCount", 1, "openCount", 0),
                Map.of("sessionId", "s-ok", "runCount", 1, "failedCount", 0, "openCount", 0))));

        var all = controller.list(request, null, null, null, false, 7, 50);
        var problems = controller.list(request, null, null, null, true, 7, 50);

        assertEquals(List.of("s-failed", "s-ok"), all.stream().map(item -> item.sessionId()).sorted().toList());
        assertEquals(List.of("s-failed"), problems.stream().map(item -> item.sessionId()).toList());
        assertEquals("芋道管理员", problems.get(0).externalUserName());
        verify(runtime, org.mockito.Mockito.times(2)).runOpsSessionStats(eq(List.of("s-failed", "s-ok")), eq(7));
    }

    @Test
    void conversationsOfOtherProjectsAreNotFound() {
        when(authorization.requirePermission(request, "runops:read")).thenReturn(operator);
        when(sessions.selectOne(any())).thenReturn(session("s-other", "secret"));

        var error = assertThrows(ResponseStatusException.class, () -> controller.detail(request, "s-other"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void detailStillShowsTheTranscriptWhenRuntimeIsDown() {
        when(authorization.requirePermission(request, "runops:read")).thenReturn(operator);
        when(operator.hasResourcePermission(eq("runops:read"), eq("PROJECT"), any(), eq("yudao-erp"))).thenReturn(true);
        when(sessions.selectOne(any())).thenReturn(session("s-1", "yudao-erp"));
        when(events.selectList(any())).thenReturn(List.of(event("user", "库存多少？", null, t0)));
        when(runtime.runOpsSessionRuns(eq("s-1"), anyInt(), anyInt())).thenThrow(new IllegalStateException("down"));

        var detail = controller.detail(request, "s-1");

        assertFalse(detail.runsAvailable());
        assertEquals(1, detail.turns().size());
        assertEquals("库存多少？", detail.conversation().lastQuestion());
        assertNull(detail.conversation().failedRunCount());
    }

    @Test
    void detailCountsFailedTurns() {
        when(authorization.requirePermission(request, "runops:read")).thenReturn(operator);
        when(operator.hasResourcePermission(eq("runops:read"), eq("PROJECT"), any(), eq("yudao-erp"))).thenReturn(true);
        when(sessions.selectOne(any())).thenReturn(session("s-1", "yudao-erp"));
        when(events.selectList(any())).thenReturn(List.of(event("user", "库存多少？", null, t0)));
        when(runtime.runOpsSessionRuns(eq("s-1"), anyInt(), anyInt())).thenReturn(ResponseEntity.ok(List.of(
                Map.of("traceId", "trace-9", "status", "FAILED", "errorCode", "TOOL_ERROR",
                        "startedAt", t0.plusSeconds(1).toString()))));

        var detail = controller.detail(request, "s-1");

        assertTrue(detail.runsAvailable());
        assertEquals(1, detail.conversation().failedRunCount());
        assertEquals("TOOL_ERROR", detail.turns().get(0).run().errorCode());
    }

    private PlatformEmbedChatEventEntity event(String role, String content, String traceId, LocalDateTime at) {
        var event = new PlatformEmbedChatEventEntity();
        event.setEventType("MESSAGE");
        event.setRole(role);
        event.setContent(content);
        event.setTraceId(traceId);
        event.setCreatedAt(at);
        return event;
    }

    private ConversationRunView run(String traceId, String status, LocalDateTime startedAt) {
        return new ConversationRunView(traceId, status, null, null, null, 10, startedAt.toString());
    }

    private PlatformEmbedSessionEntity session(String id, String projectCode) {
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId(id);
        session.setProjectCode(projectCode);
        session.setAgentId("agent-1");
        session.setExternalUserId("admin");
        session.setExternalUserName("芋道管理员");
        session.setCreatedAt(t0);
        session.setUpdatedAt(t0);
        return session;
    }

    private PlatformEmbedConversationStats stats(String sessionId, int questions) {
        var stats = new PlatformEmbedConversationStats();
        stats.setSessionId(sessionId);
        stats.setQuestionCount(questions);
        stats.setLastEventAt(t0.plusMinutes(questions));
        stats.setLastQuestion("q-" + sessionId);
        return stats;
    }
}
