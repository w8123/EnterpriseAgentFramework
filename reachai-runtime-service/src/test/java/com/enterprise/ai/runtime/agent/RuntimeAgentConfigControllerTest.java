package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentConfigControllerTest {

    @Test
    void copiesPublishedSnapshotToDraftThroughPublicConfigApi() throws Exception {
        RuntimeAgentConfigService service = mock(RuntimeAgentConfigService.class);
        RuntimeAgentConfigController controller = new RuntimeAgentConfigController(service);
        AgentConfigVersionView draft = view(4L, 4, "DRAFT");
        when(service.copyToDraft("agent-1", 3L)).thenReturn(draft);

        Method route = RuntimeAgentConfigController.class
                .getDeclaredMethod("copyToDraft", String.class, Long.class);
        assertArrayEquals(
                new String[] {"/api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft"},
                route.getAnnotation(PostMapping.class).value());
        assertEquals(draft, controller.copyToDraft("agent-1", 3L).getBody());
        verify(service).copyToDraft("agent-1", 3L);
    }

    private AgentConfigVersionView view(Long id, int versionNo, String status) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentConfigVersionView(
                id, "agent-1", versionNo, status, "AGENTSCOPE", "prompt", "model-1",
                6, 4, 2, 300_000, 180_000, 30_000, true,
                "DEV_ALLOW_ALL", "ALLOW_LIST", null, null,
                null, now, now, List.of());
    }
}
