package com.enterprise.ai.agent.platform.control.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.platform.control.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmbedRendererAuthorizationService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final EmbedRendererMapper rendererMapper;
    private final ObjectMapper objectMapper;
    private final GuardDecisionLogService guardDecisionLogService;

    public void ensureAllowed(EmbedSessionEntity session, UiRequestPayload uiRequest) {
        if (session == null || uiRequest == null || uiRequest.getExtension() == null) {
            return;
        }
        String rendererKey = asString(uiRequest.getExtension().get("rendererKey"));
        if (!StringUtils.hasText(rendererKey)) {
            return;
        }
        EmbedRendererEntity renderer = rendererMapper.selectOne(Wrappers.<EmbedRendererEntity>lambdaQuery()
                .eq(EmbedRendererEntity::getAppId, session.getAppId())
                .eq(EmbedRendererEntity::getRendererKey, rendererKey)
                .eq(EmbedRendererEntity::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (renderer == null) {
            deny(session, rendererKey, "renderer is not registered or active");
        }
        List<String> allowedAgents = readStringList(renderer.getAllowedAgentIdsJson());
        if (!allowedAgents.isEmpty() && allowedAgents.stream().noneMatch(session.getAgentId()::equals)) {
            deny(session, rendererKey, "renderer is not allowed for agent");
        }
    }

    private void deny(EmbedSessionEntity session, String rendererKey, String reason) {
        guardDecisionLogService.record(
                null,
                "EMBED_RENDERER",
                "RENDERER",
                rendererKey,
                "DENY",
                reason,
                Map.of(
                        "sessionId", session.getSessionId(),
                        "appId", session.getAppId(),
                        "agentId", session.getAgentId(),
                        "rendererKey", rendererKey));
        throw new IllegalStateException(reason + ": " + rendererKey);
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("renderer policy json is invalid");
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
