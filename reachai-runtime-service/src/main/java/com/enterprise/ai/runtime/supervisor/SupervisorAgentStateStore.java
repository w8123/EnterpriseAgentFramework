package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * AgentScope 2.0's shutdown check uses getAgentState() without the active RuntimeContext.
 * Give that per-instance read an opaque alias to the SAME owned state object. All persistent
 * reads and mutations still pass through Runtime's exact user/session and turn-lease fence.
 */
final class SupervisorAgentStateStore implements AgentStateStore {
    private final AgentStateStore fenced;
    private final RuntimeSessionMemoryKey key;
    private final String defaultSessionId = "supervisor-state-" + UUID.randomUUID();
    private Supplier<AgentState> activeState;

    SupervisorAgentStateStore(AgentStateStore fenced, RuntimeSessionMemoryKey key) {
        this.fenced = Objects.requireNonNull(fenced);
        this.key = Objects.requireNonNull(key);
        if (!key.persistent()) throw new IllegalArgumentException("A persistent owned session is required");
    }

    String defaultSessionId() { return defaultSessionId; }

    void bind(ReActAgent agent) {
        if (activeState != null) throw new IllegalStateException("State store is already bound to an Agent");
        activeState = () -> agent.getAgentState(key.stateUserKey(), key.stateSessionKey());
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String stateName, Class<T> stateType) {
        if (userId == null && defaultSessionId.equals(sessionId)
                && "agent_state".equals(stateName) && stateType == AgentState.class) {
            if (activeState == null) throw new IllegalStateException("Agent state store is not bound");
            return Optional.of(stateType.cast(activeState.get()));
        }
        return fenced.get(userId, sessionId, stateName, stateType);
    }

    @Override
    public void save(String userId, String sessionId, String stateName, State state) {
        fenced.save(userId, sessionId, stateName, state);
    }

    @Override
    public void save(String userId, String sessionId, String stateName, List<? extends State> states) {
        fenced.save(userId, sessionId, stateName, states);
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String stateName, Class<T> stateType) {
        return fenced.getList(userId, sessionId, stateName, stateType);
    }

    @Override
    public boolean exists(String userId, String sessionId) { return fenced.exists(userId, sessionId); }

    @Override
    public void delete(String userId, String sessionId) { fenced.delete(userId, sessionId); }

    @Override
    public void delete(String userId, String sessionId, String stateName) { fenced.delete(userId, sessionId, stateName); }

    @Override
    public Set<String> listSessionIds(String userId) { return fenced.listSessionIds(userId); }

    @Override
    public void close() { fenced.close(); }
}
