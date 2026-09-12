package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey;
import com.enterprise.ai.runtime.memory.RuntimeSessionOwnershipException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorAgentStateStoreTest {
    private final RuntimeSessionMemoryKey key = new RuntimeSessionMemoryKey(
            true, "tenant", "user", "agent", "public-session", "owned-user", "owned-session", "AGENT");

    @Test
    void anonymousAliasReadsTheSameActiveStateWithoutOpeningAPersistentAnonymousSlot() {
        var fenced = mock(AgentStateStore.class);
        var adapter = new SupervisorAgentStateStore(fenced, key);
        var agent = mock(ReActAgent.class);
        var state = AgentState.builder().userId(key.stateUserKey()).sessionId(key.stateSessionKey()).build();
        state.setShutdownInterrupted(true);
        when(agent.getAgentState(key.stateUserKey(), key.stateSessionKey())).thenReturn(state);
        adapter.bind(agent);
        var aliased = adapter.get(null, adapter.defaultSessionId(), "agent_state", AgentState.class).orElseThrow();
        assertSame(state, aliased);
        aliased.setShutdownInterrupted(false);
        assertFalse(state.isShutdownInterrupted());
        verifyNoInteractions(fenced);
        assertThrows(IllegalStateException.class, () -> adapter.bind(mock(ReActAgent.class)));
        assertNotEquals(adapter.defaultSessionId(), new SupervisorAgentStateStore(fenced, key).defaultSessionId());
    }

    @Test
    void aliasDoesNotAuthorizeForeignReadsOrAnyAnonymousMutation() {
        var denied = new RuntimeSessionOwnershipException("wrong owner");
        var fenced = mock(AgentStateStore.class, invocation -> { throw denied; });
        var adapter = new SupervisorAgentStateStore(fenced, key);
        var state = AgentState.builder().sessionId("owned-session").build();
        assertSame(denied, assertThrows(RuntimeSessionOwnershipException.class,
                () -> adapter.get("foreign-user", adapter.defaultSessionId(), "agent_state", AgentState.class)));
        assertSame(denied, assertThrows(RuntimeSessionOwnershipException.class,
                () -> adapter.get(null, "foreign-session", "agent_state", AgentState.class)));
        assertSame(denied, assertThrows(RuntimeSessionOwnershipException.class,
                () -> adapter.get(null, adapter.defaultSessionId(), "other_state", AgentState.class)));
        assertSame(denied, assertThrows(RuntimeSessionOwnershipException.class,
                () -> adapter.save(null, adapter.defaultSessionId(), "agent_state", state)));
        assertSame(denied, assertThrows(RuntimeSessionOwnershipException.class,
                () -> adapter.delete(null, adapter.defaultSessionId())));
    }
}
