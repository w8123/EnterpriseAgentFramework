package com.enterprise.ai.agent.mcp;

import com.enterprise.ai.agent.acl.ToolAclDecision;
import com.enterprise.ai.agent.acl.ToolAclService;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MCP 协议服务端：JSON-RPC 2.0 实现。
 * <p>
 * 支持的 method：
 * <ul>
 *     <li>{@code initialize}：握手；返回 server info / capabilities；</li>
 *     <li>{@code tools/list}：列出对该 Client 可见的 Tool/Skill；</li>
 *     <li>{@code tools/call}：调用 Tool/Skill，复用 ToolRegistry + ToolACL 决策。</li>
 * </ul>
 *
 * <p>认证：HTTP Header {@code Authorization: Bearer <apiKey>} 或 query 参数 {@code apiKey}。</p>
 *
 * <p>访问控制三层：</p>
 * <ol>
 *     <li>{@link McpVisibilityService}：系统级开关（管理员勾选哪些 Tool 允许暴露）；</li>
 *     <li>{@link McpClientEntity#getToolWhitelistJson}：Client 级白名单；</li>
 *     <li>{@link ToolAclService}：roles × tool 的 ACL 决策。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpServerEndpoint {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "enterprise-agent-framework";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final McpClientService clientService;
    private final McpVisibilityService visibilityService;
    private final McpCallLogMapper callLogMapper;
    private final ToolRegistry toolRegistry;
    private final ToolAclService toolAclService;

    @GetMapping(value = "/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> manifest() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("protocol", "MCP");
        info.put("protocolVersion", PROTOCOL_VERSION);
        info.put("serverName", SERVER_NAME);
        info.put("transports", List.of("http/jsonrpc"));
        info.put("endpoint", "/mcp/jsonrpc");
        info.put("auth", "Authorization: Bearer <apiKey>");
        return ResponseEntity.ok(info);
    }

    @PostMapping(value = "/jsonrpc", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jsonrpc(@RequestBody Map<String, Object> req,
                                                       @RequestHeader(value = "Authorization", required = false) String authHeader,
                                                       @RequestParam(value = "apiKey", required = false) String apiKeyParam,
                                                       HttpServletRequest http) {
        long t0 = System.currentTimeMillis();
        String apiKey = resolveApiKey(authHeader, apiKeyParam);
        Optional<McpClientEntity> clientOpt = clientService.authenticate(apiKey);
        Object id = req == null ? null : req.get("id");
        if (clientOpt.isEmpty()) {
            return ResponseEntity.ok(error(id, -32001, "invalid api key"));
        }
        McpClientEntity client = clientOpt.get();
        String method = req == null ? "" : String.valueOf(req.getOrDefault("method", ""));
        Object params = req == null ? null : req.get("params");
        String traceId = "mcp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String remoteIp = http == null ? null : http.getRemoteAddr();

        Map<String, Object> response;
        String toolName = null;
        boolean ok = false;
        String errorMsg = null;
        try {
            switch (method) {
                case "initialize" -> {
                    response = success(id, buildInitializeResult());
                    ok = true;
                }
                case "tools/list" -> {
                    response = success(id, Map.of("tools", listVisibleTools(client)));
                    ok = true;
                }
                case "tools/call" -> {
                    Map<String, Object> p = (params instanceof Map<?, ?> m)
                            ? objectMapper.convertValue(m, MAP_TYPE)
                            : Map.of();
                    toolName = String.valueOf(p.getOrDefault("name", ""));
                    Object args = p.get("arguments");
                    Map<String, Object> argsMap = (args instanceof Map<?, ?> m)
                            ? objectMapper.convertValue(m, MAP_TYPE)
                            : Map.of();
                    response = success(id, doToolCall(client, toolName, argsMap));
                    ok = true;
                }
                default -> response = error(id, -32601, "method not found: " + method);
            }
        } catch (RuntimeException ex) {
            errorMsg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            response = error(id, -32000, errorMsg);
            log.warn("[MCP] {} 调用失败: {}", method, errorMsg);
        }
        try {
            persistLog(client, method, toolName, ok, errorMsg, req, response, traceId, remoteIp,
                    System.currentTimeMillis() - t0);
        } catch (Exception ignored) { /* ignore */ }
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> buildInitializeResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", PROTOCOL_VERSION);
        r.put("protocolVersion", PROTOCOL_VERSION);
        r.put("capabilities", capabilities);
        r.put("serverInfo", serverInfo);
        return r;
    }

    private List<Map<String, Object>> listVisibleTools(McpClientEntity client) {
        Set<String> exposed = visibilityService.exposedToolNames();
        List<String> clientWhitelist = clientService.toolWhitelistOf(client);
        List<String> roles = clientService.rolesOf(client);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiTool tool : toolRegistry.getAllTools()) {
            String name = tool.name();
            if (!exposed.contains(name)) continue;
            if (clientWhitelist != null && !clientWhitelist.isEmpty() && !clientWhitelist.contains(name)) continue;
            ToolAclDecision decision = toolAclService.decide(roles, name, toolRegistry.isSkill(name));
            if (decision != ToolAclDecision.ALLOW && decision != ToolAclDecision.SKIPPED) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", tool.description() == null ? "" : tool.description());
            m.put("inputSchema", buildInputSchema(tool));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> buildInputSchema(AiTool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (tool.parameters() != null) {
            for (ToolParameter p : tool.parameters()) {
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", normalizeType(p.type()));
                if (p.description() != null) prop.put("description", p.description());
                properties.put(p.name(), prop);
                if (p.required()) required.add(p.name());
            }
        }
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static String normalizeType(String t) {
        if (t == null) return "string";
        return switch (t) {
            case "integer", "number", "boolean", "object", "array", "string" -> t;
            case "json" -> "object";
            default -> "string";
        };
    }

    private Map<String, Object> doToolCall(McpClientEntity client, String toolName, Map<String, Object> args) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("missing tool name");
        }
        if (!visibilityService.isExposed(toolName)) {
            throw new IllegalStateException("tool not exposed via MCP: " + toolName);
        }
        List<String> whitelist = clientService.toolWhitelistOf(client);
        if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(toolName)) {
            throw new IllegalStateException("tool not in client whitelist: " + toolName);
        }
        if (!toolRegistry.contains(toolName)) {
            throw new IllegalArgumentException("unknown tool: " + toolName);
        }
        List<String> roles = clientService.rolesOf(client);
        ToolAclDecision decision = toolAclService.decide(roles, toolName, toolRegistry.isSkill(toolName));
        if (decision == ToolAclDecision.DENY_EXPLICIT || decision == ToolAclDecision.DENY_NO_MATCH) {
            throw new IllegalStateException("ACL denied: " + decision);
        }
        Object result = toolRegistry.execute(toolName, args == null ? Map.of() : args);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", result == null ? "" : String.valueOf(result));
        return Map.of("content", List.of(content), "isError", false);
    }

    private void persistLog(McpClientEntity client, String method, String toolName, boolean success,
                            String errorMessage, Map<String, Object> reqBody, Map<String, Object> respBody,
                            String traceId, String remoteIp, long elapsedMs) {
        try {
            McpCallLogEntity row = new McpCallLogEntity();
            row.setClientId(client.getId());
            row.setClientName(client.getName());
            row.setMethod(method);
            row.setToolName(toolName);
            row.setSuccess(success);
            row.setLatencyMs(elapsedMs);
            row.setRequestBody(toJson(reqBody));
            row.setResponseBody(toJson(respBody));
            row.setErrorMessage(errorMessage);
            row.setTraceId(traceId);
            row.setRemoteIp(remoteIp);
            row.setCreatedAt(LocalDateTime.now());
            callLogMapper.insert(row);
        } catch (Exception ex) {
            log.debug("[MCP] 写日志失败（已忽略）: {}", ex.toString());
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            String s = objectMapper.writeValueAsString(obj);
            return s.length() > 8000 ? s.substring(0, 8000) + "...[truncated]" : s;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", result);
        return r;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        r.put("error", err);
        return r;
    }

    private static String resolveApiKey(String authHeader, String apiKeyParam) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring("Bearer ".length()).trim();
        }
        return apiKeyParam;
    }
}
