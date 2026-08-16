package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSessionMemoryServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "runtime-memory-class-test");
        TableInfoHelper.initTableInfo(assistant, RuntimeConversationSessionEntity.class);
        TableInfoHelper.initTableInfo(assistant, RuntimeConversationEventEntity.class);
    }

    @Test
    void persistentKeysUseOnlyTrustedIdentityAndSeparateTenantAgentAndSession() {
        RuntimeSessionMemoryService service = service(new InMemoryAgentStateStore());
        RuntimeAgentView agent = agent("agent-a");

        RuntimeSessionMemoryKey tenantA = service.resolve(
                agent, "session-1", WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));
        RuntimeSessionMemoryKey tenantB = service.resolve(
                agent, "session-1", WorkflowExecutionIdentity.fromAgent("tenant-b", 7L, "p", "user-1"));
        RuntimeSessionMemoryKey anotherAgent = service.resolve(
                agent("agent-b"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));
        RuntimeSessionMemoryKey untrusted = service.resolve(
                agent, "session-1", WorkflowExecutionIdentity.untrustedDebug());

        assertTrue(tenantA.persistent());
        assertFalse(untrusted.persistent());
        assertNotEquals(tenantA.stateUserKey(), tenantB.stateUserKey());
        assertNotEquals(tenantA.stateSessionKey(), anotherAgent.stateSessionKey());
        assertFalse(tenantA.stateUserKey().contains("user-1"));
        assertFalse(tenantA.stateSessionKey().contains("session-1"));
    }

    @Test
    void rejectsAnOversizedSessionIdBeforeCreatingPersistentState() {
        RuntimeSessionMemoryService service = service(new InMemoryAgentStateStore());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.resolve(agent("agent-a"), "s".repeat(129),
                        WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1")));
    }

    @Test
    void historySurvivesServiceRecreationThroughSharedAgentStateStore() {
        InMemoryAgentStateStore sharedStore = new InMemoryAgentStateStore();
        RuntimeSessionMemoryService first = service(sharedStore);
        RuntimeSessionMemoryKey key = first.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));
        AgentState state = AgentState.builder()
                .sessionId(key.stateSessionKey())
                .userId(key.stateUserKey())
                .context(List.of(
                        msg(MsgRole.USER, "我叫小明"),
                        msg(MsgRole.ASSISTANT, "记住了")))
                .build();
        sharedStore.save(key.stateUserKey(), key.stateSessionKey(), RuntimeSessionMemoryService.STATE_NAME, state);

        RuntimeSessionMemoryService afterRestart = service(sharedStore);
        List<Msg> history = afterRestart.history(key);

        assertEquals(2, history.size());
        assertEquals("我叫小明", history.get(0).getTextContent());
        assertEquals("记住了", history.get(1).getTextContent());
    }

    @Test
    void clearRequiresTrustedOwnerAndDeletesOnlyItsStateSlot() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "runtime-memory-test"),
                RuntimeConversationSessionEntity.class);
        InMemoryAgentStateStore sharedStore = new InMemoryAgentStateStore();
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeSessionMemoryService service = service(sharedStore, sessionMapper);
        RuntimeSessionMemoryKey key = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));
        AgentState state = AgentState.builder()
                .sessionId(key.stateSessionKey()).userId(key.stateUserKey()).build();
        sharedStore.save(key.stateUserKey(), key.stateSessionKey(), RuntimeSessionMemoryService.STATE_NAME, state);
        RuntimeConversationSessionEntity row = new RuntimeConversationSessionEntity();
        row.setId(1L);
        row.setStateUserKey(key.stateUserKey());
        row.setStateSessionKey(key.stateSessionKey());
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(row);

        service.clear("session-1", WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(sharedStore.exists(key.stateUserKey(), key.stateSessionKey()));

        service.clear("session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));
        assertFalse(sharedStore.exists(key.stateUserKey(), key.stateSessionKey()));
        verify(sessionMapper).update(any(), any(Wrapper.class));
    }

    @Test
    void ownerMismatchIsRejectedBeforeStateCanBeLoaded() {
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationSessionEntity existing = new RuntimeConversationSessionEntity();
        existing.setId(1L);
        existing.setTenantId("tenant-a");
        existing.setSessionId("session-1");
        existing.setUserId("owner-a");
        existing.setAgentId("agent-a");
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        RuntimeSessionMemoryService service = service(new InMemoryAgentStateStore(), sessionMapper);
        RuntimeSessionMemoryKey attacker = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "attacker"));

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeSessionOwnershipException.class,
                () -> service.prepare(attacker, agent("agent-a"), 1L));
    }

    @Test
    void purgingSessionCannotBeReactivatedOrAcquireANewTurn() {
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationSessionEntity existing = existingSession(
                "tenant-a", "session-1", "user-1", "agent-a");
        existing.setStatus("PURGING");
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        RuntimeSessionMemoryService service = service(new InMemoryAgentStateStore(), sessionMapper);
        RuntimeSessionMemoryKey key = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeSessionBusyException.class,
                () -> service.acquireTurn(key, agent("agent-a"), 7L));
    }

    @Test
    void lostTurnLeaseIsFencedBeforeAnyConversationEventIsWritten() {
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationEventMapper eventMapper = mock(RuntimeConversationEventMapper.class);
        RuntimeConversationSessionEntity existing = existingSession(
                "tenant-a", "session-1", "user-1", "agent-a");
        existing.setTurnLeaseOwner("new-owner");
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(sessionMapper.update(any(), any(Wrapper.class))).thenReturn(0);
        RuntimeSessionMemoryService service = service(
                new InMemoryAgentStateStore(), sessionMapper, eventMapper);
        RuntimeSessionMemoryKey key = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeSessionBusyException.class,
                () -> service.recordTurn(
                        key, agent("agent-a"), 7L, "trace-1", "turn-1",
                        "question", "answer", "OK", true, false, false, "old-owner"));

        verify(eventMapper, never()).insert(any(RuntimeConversationEventEntity.class));
    }

    @Test
    void recordsDistinctTurnsWithinSameTraceAndDeduplicatesRetry() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "runtime-memory-turn-test"),
                RuntimeConversationEventEntity.class);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationEventMapper eventMapper = mock(RuntimeConversationEventMapper.class);
        RuntimeConversationSessionEntity session = existingSession("tenant-a", "session-1", "user-1", "agent-a");
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session);
        List<RuntimeConversationEventEntity> inserted = new ArrayList<>();
        when(eventMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L, 2L);
        org.mockito.Mockito.doAnswer(invocation -> {
            RuntimeConversationEventEntity event = invocation.getArgument(0);
            inserted.add(event);
            return 1;
        }).when(eventMapper).insert(any(RuntimeConversationEventEntity.class));
        RuntimeSessionMemoryService service = service(stateStore, sessionMapper, eventMapper);
        RuntimeSessionMemoryKey key = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));

        service.recordTurn(key, agent("agent-a"), 7L, "trace-1", "turn-1",
                "第一次", "等待确认", "WAITING", false, true, false);
        service.recordTurn(key, agent("agent-a"), 7L, "trace-1", "turn-2",
                "确认", "已完成", "COMPLETED", true, false, false);
        service.recordTurn(key, agent("agent-a"), 7L, "trace-1", "turn-2",
                "确认", "已完成", "COMPLETED", true, false, false);

        assertEquals(4, inserted.size());
        assertEquals(List.of("turn-1", "turn-1", "turn-2", "turn-2"),
                inserted.stream().map(RuntimeConversationEventEntity::getTurnId).toList());
        assertEquals(List.of("第一次", "等待确认", "确认", "已完成"),
                service.history(key).stream().map(Msg::getTextContent).toList());
    }

    @Test
    void repeatedIdenticalUserMessagesRemainSeparateWhenAgentScopeDidNotSaveThem() {
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationEventMapper eventMapper = mock(RuntimeConversationEventMapper.class);
        RuntimeConversationSessionEntity session = existingSession("tenant-a", "session-1", "user-1", "agent-a");
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session);
        when(eventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        RuntimeSessionMemoryService service = service(stateStore, sessionMapper, eventMapper);
        RuntimeSessionMemoryKey key = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));

        service.recordTurn(key, agent("agent-a"), 7L, "trace-1", "turn-1",
                "继续", "第一步完成", "OK", true, false, false);
        service.recordTurn(key, agent("agent-a"), 7L, "trace-2", "turn-2",
                "继续", "第二步完成", "OK", true, false, false);

        assertEquals(List.of("继续", "第一步完成", "继续", "第二步完成"),
                service.history(key).stream().map(Msg::getTextContent).toList());
    }

    @Test
    void replacesInternalContinuationPromptWithTheUsersVisibleSubmission() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "runtime-memory-continuation-test"),
                RuntimeConversationSessionEntity.class);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationEventMapper eventMapper = mock(RuntimeConversationEventMapper.class);
        RuntimeConversationSessionEntity session = existingSession("tenant-a", "session-1", "user-1", "agent-a");
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session);
        when(eventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        RuntimeSessionMemoryService service = service(stateStore, sessionMapper, eventMapper);
        RuntimeSessionMemoryKey key = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));
        stateStore.save(key.stateUserKey(), key.stateSessionKey(), RuntimeSessionMemoryService.STATE_NAME,
                AgentState.builder()
                        .sessionId(key.stateSessionKey())
                        .userId(key.stateUserKey())
                        .context(List.of(
                                msg(MsgRole.USER, "原始请求"),
                                msg(MsgRole.ASSISTANT, "请确认"),
                                msg(MsgRole.USER, "Internal continuation instruction"),
                                msg(MsgRole.ASSISTANT, "已完成")))
                        .build());

        service.recordTurn(key, agent("agent-a"), 7L, "trace-1", "turn-2",
                "Interaction response: confirm", "已完成", "COMPLETED", true, false, true);

        assertEquals(List.of("原始请求", "请确认", "Interaction response: confirm", "已完成"),
                service.history(key).stream().map(Msg::getTextContent).toList());
    }

    @Test
    void trimsThePersistedAgentStateBeforeAndAfterEveryTurn() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "runtime-memory-window-test"),
                RuntimeConversationSessionEntity.class);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeConversationEventMapper eventMapper = mock(RuntimeConversationEventMapper.class);
        RuntimeConversationSessionEntity session = existingSession("tenant-a", "session-1", "user-1", "agent-a");
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session);
        when(sessionMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(eventMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        RuntimeSessionMemoryService service = new RuntimeSessionMemoryService(
                stateStore,
                sessionMapper,
                eventMapper,
                new RuntimeSessionMemoryProperties(
                        true, "in-memory", "", "", "", false, 4, 65_535, 600));
        RuntimeSessionMemoryKey key = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));
        stateStore.save(key.stateUserKey(), key.stateSessionKey(), RuntimeSessionMemoryService.STATE_NAME,
                AgentState.builder()
                        .sessionId(key.stateSessionKey())
                        .userId(key.stateUserKey())
                        .context(List.of(
                                msg(MsgRole.USER, "u1"),
                                msg(MsgRole.ASSISTANT, "a1"),
                                msg(MsgRole.USER, "u2"),
                                msg(MsgRole.ASSISTANT, "a2"),
                                msg(MsgRole.USER, "u3"),
                                msg(MsgRole.ASSISTANT, "a3")))
                        .build());

        service.acquireTurn(key, agent("agent-a"), 7L);

        assertEquals(List.of("u2", "a2", "u3", "a3"), persistedContext(stateStore, key));

        service.recordTurn(key, agent("agent-a"), 7L, "trace-4", "turn-4",
                "u4", "a4", "COMPLETED", true, false, false);

        assertEquals(List.of("u3", "a3", "u4", "a4"), persistedContext(stateStore, key));
    }

    @Test
    void agentScopeStateSaveIsFencedByTheExactActiveTurnOwner() {
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeConversationSessionMapper sessionMapper = mock(RuntimeConversationSessionMapper.class);
        RuntimeSessionMemoryService service = service(
                stateStore, sessionMapper, mock(RuntimeConversationEventMapper.class));
        RuntimeSessionMemoryKey key = service.resolve(
                agent("agent-a"), "session-1",
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "p", "user-1"));
        AgentState state = AgentState.builder()
                .sessionId(key.stateSessionKey())
                .userId(key.stateUserKey())
                .context(List.of(msg(MsgRole.USER, "must not return after erase")))
                .build();
        AgentStateStore fenced = service.stateStoreForTurn(key, "old-turn-owner");
        when(sessionMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        RuntimeSessionBusyException failure = assertThrows(
                RuntimeSessionBusyException.class,
                () -> fenced.save(key.stateUserKey(), key.stateSessionKey(),
                        RuntimeSessionMemoryService.STATE_NAME, state));

        assertTrue(failure.getMessage().contains("lease was lost"));
        assertFalse(stateStore.exists(key.stateUserKey(), key.stateSessionKey()));

        when(sessionMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        fenced.save(key.stateUserKey(), key.stateSessionKey(),
                RuntimeSessionMemoryService.STATE_NAME, state);
        assertTrue(stateStore.exists(key.stateUserKey(), key.stateSessionKey()));
    }

    private static RuntimeSessionMemoryService service(InMemoryAgentStateStore stateStore) {
        return service(stateStore, mock(RuntimeConversationSessionMapper.class));
    }

    private static RuntimeSessionMemoryService service(InMemoryAgentStateStore stateStore,
                                                       RuntimeConversationSessionMapper sessionMapper) {
        return service(stateStore, sessionMapper, mock(RuntimeConversationEventMapper.class));
    }

    private static RuntimeSessionMemoryService service(InMemoryAgentStateStore stateStore,
                                                       RuntimeConversationSessionMapper sessionMapper,
                                                       RuntimeConversationEventMapper eventMapper) {
        return new RuntimeSessionMemoryService(
                stateStore,
                sessionMapper,
                eventMapper,
                new RuntimeSessionMemoryProperties(
                        true, "in-memory", "", "", "", false, 40, 65_535, 600));
    }

    private static RuntimeConversationSessionEntity existingSession(
            String tenantId, String sessionId, String userId, String agentId) {
        RuntimeConversationSessionEntity session = new RuntimeConversationSessionEntity();
        session.setId(1L);
        session.setTenantId(tenantId);
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setAgentId(agentId);
        session.setEventCount(0);
        session.setStatus("ACTIVE");
        return session;
    }

    private static Msg msg(MsgRole role, String content) {
        return Msg.builder().name(role.name().toLowerCase()).role(role).textContent(content).build();
    }

    private static List<String> persistedContext(InMemoryAgentStateStore stateStore,
                                                 RuntimeSessionMemoryKey key) {
        return stateStore.get(key.stateUserKey(), key.stateSessionKey(),
                        RuntimeSessionMemoryService.STATE_NAME, AgentState.class)
                .orElseThrow()
                .getContext()
                .stream()
                .map(Msg::getTextContent)
                .toList();
    }

    private static RuntimeAgentView agent(String id) {
        return new RuntimeAgentView(id, 7L, "p", id, id, null, "PROJECT", null, true,
                null, null, null, null, "AGENTSCOPE", 0, null, null);
    }
}
