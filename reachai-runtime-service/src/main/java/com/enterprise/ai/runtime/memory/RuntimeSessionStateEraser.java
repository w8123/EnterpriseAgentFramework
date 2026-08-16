package com.enterprise.ai.runtime.memory;

import io.agentscope.core.state.AgentStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Erases session-scoped state without reading or logging its contents. */
@Component
public class RuntimeSessionStateEraser {

    private final AgentStateStore stateStore;
    private final RuntimeToolResultArtifactService artifactService;

    @Autowired
    public RuntimeSessionStateEraser(
            AgentStateStore stateStore,
            ObjectProvider<RuntimeToolResultArtifactService> artifactServiceProvider) {
        this(stateStore, artifactServiceProvider.getIfAvailable());
    }

    RuntimeSessionStateEraser(
            AgentStateStore stateStore,
            RuntimeToolResultArtifactService artifactService) {
        this.stateStore = stateStore;
        this.artifactService = artifactService;
    }

    /** User clear: erase resumable state and ciphertext, retaining bounded artifact tombstones. */
    public void clearTransient(RuntimeConversationSessionEntity session) {
        stateStore.delete(session.getStateUserKey(), session.getStateSessionKey());
        if (artifactService != null) {
            artifactService.scrubSession(
                    session.getStateUserKey(), session.getStateSessionKey(), session.getAgentId());
        }
    }

    /** Retention/manual erase: remove state plus all session-scoped artifact rows. */
    public void purgeAll(RuntimeConversationSessionEntity session) {
        stateStore.delete(session.getStateUserKey(), session.getStateSessionKey());
        if (artifactService != null) {
            artifactService.purgeSession(
                    session.getStateUserKey(), session.getStateSessionKey(), session.getAgentId());
        }
    }
}
