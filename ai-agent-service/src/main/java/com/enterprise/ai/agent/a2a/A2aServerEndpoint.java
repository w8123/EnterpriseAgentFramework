package com.enterprise.ai.agent.a2a;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.agent.AgentVersionService;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.model.AgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A2A 协议服务端
 * <p>
 * 实现 Google Agent2Agent 协议 0.2.x 子集：
 * <ul>
 *   <li>{@code GET /a2a/{agentKey}/.well-known/agent.json} —— AgentCard 公告</li>
 *   <li>{@code POST /a2a/{agentKey}/jsonrpc} —— JSON-RPC：message/send、tasks/get、tasks/cancel</li>
 * </ul>
 * <p>
 * 底层映射到 {@link AgentRouter#executeByDefinition} 走和管理端 {@code /api/v1/agents/{key}/chat}
 * 一致的执行链路（共享 trace / Tool ACL / sideEffect 闸口）。
 */
@Slf4j
@RestController
@RequestMapping("/a2a")
@RequiredArgsConstructor
public class A2aServerEndpoint {

    private final A2aEndpointService endpointService;
    private final A2aCallLogService callLogService;
    private final AgentDefinitionService agentDefinitionService;
    private final AgentVersionService versionService;
    private final AgentRouter agentRouter;
    private final ObjectMapper objectMapper;

    @GetMapping(value = "/{agentKey}/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> agentCard(@PathVariable("agentKey") String agentKey,
                                       HttpServletRequest req) {
        long start = System.currentTimeMillis();
        Optional<A2aEndpointEntity> opt = endpointService.findByAgentKey(agentKey);
        if (opt.isEmpty() || !Boolean.TRUE.equals(opt.get().getEnabled())) {
            callLogService.log(opt.map(A2aEndpointEntity::getId).orElse(null),
                    agentKey, null, "card", false, System.currentTimeMillis() - start,
                    null, null, "endpoint not found or disabled", null, remoteIp(req));
            return ResponseEntity.status(404).body(Map.of("error", "A2A endpoint not found: " + agentKey));
        }
        Map<String, Object> card = new LinkedHashMap<>(endpointService.parseCard(opt.get()));
        // 补 url 字段（A2A 0.2 规范要求）
        String base = req.getRequestURL().toString();
        String url = base.replace("/.well-known/agent.json", "/jsonrpc");
        card.put("url", url);
        callLogService.log(opt.get().getId(), agentKey, null, "card", true,
                System.currentTimeMillis() - start, null, writeJson(card), null, null, remoteIp(req));
        return ResponseEntity.ok(card);
    }

    @PostMapping(value = "/{agentKey}/jsonrpc", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> jsonRpc(@PathVariable("agentKey") String agentKey,
                                     @RequestBody JsonNode body,
                                     HttpServletRequest req) {
        long start = System.currentTimeMillis();
        Object id = body.has("id") ? unwrap(body.get("id")) : null;
        String method = body.has("method") ? body.get("method").asText() : null;
        JsonNode params = body.get("params");

        Optional<A2aEndpointEntity> opt = endpointService.findByAgentKey(agentKey);
        if (opt.isEmpty() || !Boolean.TRUE.equals(opt.get().getEnabled())) {
            return ResponseEntity.status(404).body(error(id, -32004, "A2A endpoint not found: " + agentKey));
        }
        A2aEndpointEntity endpoint = opt.get();

        if (method == null || method.isBlank()) {
            return ResponseEntity.ok(error(id, -32600, "missing method"));
        }

        try {
            switch (method) {
                case "message/send":
                    return ResponseEntity.ok(handleMessageSend(endpoint, id, params, req, start));
                case "tasks/get":
                    return ResponseEntity.ok(handleTaskGet(id, params));
                case "tasks/cancel":
                    return ResponseEntity.ok(handleTaskCancel(id, params));
                default:
                    return ResponseEntity.ok(error(id, -32601, "method not found: " + method));
            }
        } catch (Exception e) {
            log.error("[A2A] 处理 JSON-RPC 异常: {}", e.getMessage(), e);
            callLogService.log(endpoint.getId(), agentKey, null, method == null ? "unknown" : method,
                    false, System.currentTimeMillis() - start, body.toString(), null, e.getMessage(), null, remoteIp(req));
            return ResponseEntity.ok(error(id, -32603, "internal error: " + e.getMessage()));
        }
    }

    private Map<String, Object> handleMessageSend(A2aEndpointEntity endpoint, Object id, JsonNode params,
                                                  HttpServletRequest req, long start) {
        String agentKey = endpoint.getAgentKey();

        // 抽取 message.parts[*].text -> userText
        String userText = extractText(params == null ? null : params.get("message"));
        String contextId = optString(params, "contextId");
        if (contextId == null || contextId.isBlank()) {
            contextId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        String userId = optDeep(params, "metadata", "userId");
        if (userId == null) userId = "a2a-anonymous";
        String taskId = UUID.randomUUID().toString();

        // 解析 Agent Definition + 版本
        AgentDefinition head = agentDefinitionService.findByKeySlug(agentKey).orElse(null);
        if (head == null || !head.isEnabled()) {
            callLogService.log(endpoint.getId(), agentKey, taskId, "message/send", false,
                    System.currentTimeMillis() - start, params == null ? null : params.toString(),
                    null, "agent not found or disabled", null, remoteIp(req));
            return error(id, -32004, "agent disabled or missing: " + agentKey);
        }
        AgentDefinition snapshot = versionService.resolveActiveSnapshot(head.getId(), userId);
        if (snapshot == null) snapshot = head;

        AgentResult result;
        try {
            result = agentRouter.executeByDefinition(snapshot, contextId, userId, userText, null);
        } catch (Exception e) {
            callLogService.log(endpoint.getId(), agentKey, taskId, "message/send", false,
                    System.currentTimeMillis() - start, params == null ? null : params.toString(),
                    null, e.getMessage(), null, remoteIp(req));
            return error(id, -32603, "agent execution failed: " + e.getMessage());
        }

        Map<String, Object> task = buildTask(taskId, contextId, agentKey, userText, result);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", task);

        callLogService.log(endpoint.getId(), agentKey, taskId, "message/send", true,
                System.currentTimeMillis() - start, params == null ? null : params.toString(),
                writeJson(task), null,
                result.getMetadata() == null ? null : (String) result.getMetadata().get("traceId"),
                remoteIp(req));
        return resp;
    }

    /**
     * 注：本仓 ReAct 一次性执行模型不持久化 Task 状态，tasks/get 仅返回占位
     * "completed" 状态以满足协议格式；若后续接入 Task Persistence，可改为查 a2a_call_log + tool_call_log 拼装。
     */
    private Map<String, Object> handleTaskGet(Object id, JsonNode params) {
        String taskId = optString(params, "id");
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", taskId);
        task.put("kind", "task");
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", "unknown");
        status.put("message", Map.of("role", "agent",
                "parts", List.of(Map.of("kind", "text", "text", "本服务为无状态执行，未持久化历史任务"))));
        task.put("status", status);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", task);
        return resp;
    }

    private Map<String, Object> handleTaskCancel(Object id, JsonNode params) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", Map.of("id", optString(params, "id"), "kind", "task",
                "status", Map.of("state", "canceled")));
        return resp;
    }

    private Map<String, Object> buildTask(String taskId, String contextId, String agentKey,
                                          String userText, AgentResult result) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", taskId);
        task.put("kind", "task");
        task.put("contextId", contextId);

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", result.isSuccess() ? "completed" : "failed");
        Map<String, Object> agentMessage = new LinkedHashMap<>();
        agentMessage.put("role", "agent");
        agentMessage.put("messageId", UUID.randomUUID().toString());
        agentMessage.put("parts", List.of(Map.of("kind", "text", "text",
                result.getAnswer() == null ? "" : result.getAnswer())));
        status.put("message", agentMessage);
        task.put("status", status);

        // history：保留输入 + 输出，便于客户端直接拼会话
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("messageId", UUID.randomUUID().toString());
        userMessage.put("parts", List.of(Map.of("kind", "text", "text", userText == null ? "" : userText)));
        task.put("history", List.of(userMessage, agentMessage));

        // artifacts 中带回工具调用 trace，方便外部 Agent 系统观测
        if (result.getToolResults() != null && !result.getToolResults().isEmpty()) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("artifactId", UUID.randomUUID().toString());
            artifact.put("name", "tool-trace");
            artifact.put("parts", List.of(Map.of("kind", "data", "data", result.getToolResults())));
            task.put("artifacts", List.of(artifact));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agentKey", agentKey);
        if (result.getMetadata() != null) {
            meta.putAll(result.getMetadata());
        }
        task.put("metadata", meta);
        return task;
    }

    private String extractText(JsonNode message) {
        if (message == null || !message.has("parts")) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : message.get("parts")) {
            if (part.has("kind") && "text".equals(part.get("kind").asText()) && part.has("text")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(part.get("text").asText());
            }
        }
        return sb.toString();
    }

    private String optString(JsonNode params, String name) {
        if (params == null || !params.has(name) || params.get(name).isNull()) return null;
        return params.get(name).asText();
    }

    private String optDeep(JsonNode params, String first, String second) {
        if (params == null || !params.has(first)) return null;
        JsonNode n = params.get(first);
        if (n == null || !n.has(second) || n.get(second).isNull()) return null;
        return n.get(second).asText();
    }

    private Object unwrap(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isInt()) return n.asInt();
        if (n.isLong()) return n.asLong();
        if (n.isNumber()) return n.numberValue();
        return n.asText();
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", err);
        return resp;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private String remoteIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
