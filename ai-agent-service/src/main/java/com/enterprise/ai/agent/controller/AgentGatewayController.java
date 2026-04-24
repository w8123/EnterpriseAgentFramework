package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.agent.AgentVersionService;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.ChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 发布端点（Phase 3.0 Agent Studio）
 * <p>
 * 路径：{@code /api/v1/agents/{key}/chat}，其中 {@code key} 是
 * {@link AgentDefinition#getKeySlug()}（人类可读 slug，比如 {@code order-assistant}）。
 * <p>
 * 执行路径：
 * <ol>
 *   <li>按 {@code keySlug} 找到 Agent；</li>
 *   <li>调 {@link AgentVersionService#resolveActiveSnapshot(String, String)} 按 userId
 *       hash % 100 对比 {@code rollout_percent} 选出目标版本快照；</li>
 *   <li>若没有任何 ACTIVE 版本，直接回落到当前 definition 态（相当于"草稿执行"），
 *       便于联调期没有正式发布时也能跑起来；</li>
 *   <li>交给 {@link AgentRouter#executeByDefinition} 走实际的 ReAct 执行链路。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentGatewayController {

    private final AgentDefinitionService definitionService;
    private final AgentVersionService versionService;
    private final AgentRouter agentRouter;

    @PostMapping("/{key}/chat")
    public ResponseEntity<ChatResponse> chat(@PathVariable("key") String key,
                                             @Valid @RequestBody ChatRequest request) {
        log.info("[AgentGateway] 收到请求: key={}, userId={}", key, request.getUserId());

        AgentDefinition head = definitionService.findByKeySlug(key).orElse(null);
        if (head == null || !head.isEnabled()) {
            return ResponseEntity.badRequest().body(ChatResponse.error("Agent 不存在或已禁用: " + key));
        }

        AgentDefinition snapshot = versionService.resolveActiveSnapshot(head.getId(), request.getUserId());
        if (snapshot == null) {
            log.info("[AgentGateway] 无 ACTIVE 版本，使用当前草稿执行: key={}", key);
            snapshot = head;
        }

        String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                ? request.getSessionId()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        AgentResult result = agentRouter.executeByDefinition(
                snapshot, sessionId, request.getUserId(), request.getMessage(),
                request.getRoles());

        Map<String, Object> metadata = result.getMetadata() == null ? new HashMap<>()
                : new HashMap<>(result.getMetadata());
        metadata.put("agentKey", key);
        if (snapshot.getExtra() != null && snapshot.getExtra().containsKey("__version")) {
            metadata.put("version", snapshot.getExtra().get("__version"));
        }

        ChatResponse response = ChatResponse.builder()
                .sessionId(sessionId)
                .answer(result.getAnswer())
                .intentType(snapshot.getIntentType())
                .toolCalls(toList(metadata.get("toolCalls")))
                .reasoningSteps(toList(metadata.get("steps")))
                .metadata(metadata)
                .build();
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private List<String> toList(Object value) {
        if (value instanceof List<?>) {
            return (List<String>) value;
        }
        return null;
    }
}
