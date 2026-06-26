package com.enterprise.ai.agent.platform.control.identity;

import com.enterprise.ai.agent.platform.control.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbedRendererAuthorizationServiceTest {

    @Test
    void deniesCustomRendererWhenAgentIsNotAllowed() {
        EmbedRendererMapper mapper = mock(EmbedRendererMapper.class);
        GuardDecisionLogService audit = mock(GuardDecisionLogService.class);
        EmbedRendererEntity renderer = new EmbedRendererEntity();
        renderer.setAppId("bzsdk");
        renderer.setRendererKey("bzsdk.teamProfile");
        renderer.setStatus("ACTIVE");
        renderer.setAllowedAgentIdsJson("[\"other-agent\"]");
        when(mapper.selectOne(any())).thenReturn(renderer);

        EmbedRendererAuthorizationService service =
                new EmbedRendererAuthorizationService(mapper, new ObjectMapper(), audit);
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-1");
        session.setAppId("bzsdk");
        session.setAgentId("team-agent");
        UiRequestPayload uiRequest = UiRequestPayload.builder()
                .component("custom")
                .extension(Map.of("rendererKey", "bzsdk.teamProfile"))
                .build();

        assertThrows(IllegalStateException.class, () -> service.ensureAllowed(session, uiRequest));
        verify(audit).record(
                eq(null),
                eq("EMBED_RENDERER"),
                eq("RENDERER"),
                eq("bzsdk.teamProfile"),
                eq("DENY"),
                eq("renderer is not allowed for agent"),
                any(Map.class));
    }
}
