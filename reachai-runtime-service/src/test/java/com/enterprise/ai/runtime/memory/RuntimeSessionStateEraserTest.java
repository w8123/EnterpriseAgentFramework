package com.enterprise.ai.runtime.memory;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RuntimeSessionStateEraserTest {

    @Test
    void springCanSelectTheRuntimeInjectionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentStateStore.class, InMemoryAgentStateStore::new);
            context.register(RuntimeSessionStateEraser.class);
            context.refresh();

            assertNotNull(context.getBean(RuntimeSessionStateEraser.class));
        }
    }

    @Test
    void clearAndPurgeUseDifferentArtifactSemanticsButBothDeleteAgentState() {
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeToolResultArtifactService artifacts = mock(RuntimeToolResultArtifactService.class);
        RuntimeSessionStateEraser eraser = new RuntimeSessionStateEraser(stateStore, artifacts);
        RuntimeConversationSessionEntity session = new RuntimeConversationSessionEntity();
        session.setStateUserKey("user-key");
        session.setStateSessionKey("session-key");
        session.setAgentId("agent-a");
        stateStore.save("user-key", "session-key", RuntimeSessionMemoryService.STATE_NAME,
                AgentState.builder().userId("user-key").sessionId("session-key").build());

        eraser.clearTransient(session);
        assertFalse(stateStore.exists("user-key", "session-key"));
        verify(artifacts).scrubSession("user-key", "session-key", "agent-a");

        stateStore.save("user-key", "session-key", RuntimeSessionMemoryService.STATE_NAME,
                AgentState.builder().userId("user-key").sessionId("session-key").build());
        eraser.purgeAll(session);
        assertFalse(stateStore.exists("user-key", "session-key"));
        verify(artifacts).purgeSession("user-key", "session-key", "agent-a");
    }
}
