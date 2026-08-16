package com.enterprise.ai.runtime.memory;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.util.List;
import java.util.Optional;
import java.util.Set;

final class WindowedAgentStateStore implements AgentStateStore {

    private final AgentStateStore delegate;
    private final int maxContextMessages;

    WindowedAgentStateStore(AgentStateStore delegate, int maxContextMessages) {
        this.delegate = delegate;
        this.maxContextMessages = Math.max(4, maxContextMessages);
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        trim(state);
        delegate.save(userId, sessionId, key, state);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        if (states != null) {
            states.forEach(this::trim);
        }
        delegate.save(userId, sessionId, key, states);
    }

    @Override
    public <T extends State> Optional<T> get(String userId,
                                             String sessionId,
                                             String key,
                                             Class<T> type) {
        return delegate.get(userId, sessionId, key, type);
    }

    @Override
    public <T extends State> List<T> getList(String userId,
                                             String sessionId,
                                             String key,
                                             Class<T> type) {
        return delegate.getList(userId, sessionId, key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private void trim(State state) {
        if (!(state instanceof AgentState agentState)) {
            return;
        }
        List<?> context = agentState.contextMutable();
        while (context.size() > maxContextMessages) {
            context.remove(0);
        }
    }
}
