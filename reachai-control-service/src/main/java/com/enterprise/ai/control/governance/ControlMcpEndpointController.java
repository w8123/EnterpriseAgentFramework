package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ControlMcpEndpointController {

    private final ControlMcpClientMapper clientMapper;
    private final ControlMcpVisibilityMapper visibilityMapper;
    private final ControlMcpCallLogMapper callLogMapper;
    private final RuntimeProxyClient runtimeClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/mcp/manifest")
    public ResponseEntity<Map<String, Object>> manifest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ReachAI MCP");
        body.put("protocolVersion", "2024-11-05");
        body.put("transport", Map.of(
                "type", "http",
                "url", "/mcp/jsonrpc"));
        body.put("capabilities", Map.of(
                "tools", Map.of()));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/mcp/jsonrpc")
    public ResponseEntity<Map<String, Object>> jsonRpc(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request) {
        ControlMcpClientEntity client = authenticate(authorization);
        if (client == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_token"));
        }
        client.setLastUsedAt(LocalDateTime.now());
        clientMapper.updateById(client);

        long started = System.nanoTime();
        String method = text(request == null ? null : request.get("method"));
        Object id = request == null ? null : request.get("id");
        try {
            Map<String, Object> response = switch (method == null ? "" : method) {
                case "tools/list" -> success(id, Map.of("tools", visibleTools(client)));
                case "tools/call" -> handleToolsCall(client, id, mapValue(request.get("params")));
                default -> error(id, -32601, "Method not found: " + method);
            };
            recordCall(client, method, request, response, !response.containsKey("error"), started, null);
            return ResponseEntity.ok(response);
        } catch (RuntimeException ex) {
            Map<String, Object> response = error(id, -32603, ex.getMessage());
            recordCall(client, method, request, response, false, started, ex.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    private ControlMcpClientEntity authenticate(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String apiKey = authorization.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        ControlMcpClientEntity client = clientMapper.selectOne(new LambdaQueryWrapper<ControlMcpClientEntity>()
                .eq(ControlMcpClientEntity::getApiKeyHash, sha256(apiKey))
                .last("limit 1"));
        if (client == null || Boolean.FALSE.equals(client.getEnabled())) {
            return null;
        }
        return client.getExpiresAt() != null && client.getExpiresAt().isBefore(LocalDateTime.now()) ? null : client;
    }

    private List<Map<String, Object>> visibleTools(ControlMcpClientEntity client) {
        List<String> whitelist = parseStringList(client.getToolWhitelistJson());
        return visibilityMapper.selectList(new LambdaQueryWrapper<ControlMcpVisibilityEntity>()
                        .eq(ControlMcpVisibilityEntity::getExposed, true)
                        .orderByAsc(ControlMcpVisibilityEntity::getTargetKind)
                        .orderByAsc(ControlMcpVisibilityEntity::getTargetName))
                .stream()
                .filter(item -> whitelist.isEmpty() || whitelist.contains(item.getTargetName()))
                .map(this::toolView)
                .toList();
    }

    private Map<String, Object> handleToolsCall(ControlMcpClientEntity client,
                                                Object id,
                                                Map<String, Object> params) {
        String name = text(params.get("name"));
        if (!StringUtils.hasText(name)) {
            return error(id, -32602, "tools/call params.name is required");
        }
        if (visibleTools(client).stream().noneMatch(tool -> name.equals(tool.get("name")))) {
            return error(id, -32004, "tool is not visible to this MCP client: " + name);
        }
        Map<String, Object> runtimeBody = new LinkedHashMap<>();
        runtimeBody.put("arguments", mapValue(params.get("arguments")));
        runtimeBody.put("intentHint", "MCP_TOOLS_CALL");
        runtimeBody.put("metadata", Map.of(
                "mcpClientId", client.getId(),
                "mcpClientName", client.getName(),
                "toolName", name));
        ResponseEntity<Object> runtimeResponse = runtimeClient.executeRuntimeTool(name, runtimeBody);
        if (!runtimeResponse.getStatusCode().is2xxSuccessful()) {
            return error(id, -32603, "runtime tool execution failed: " + runtimeResponse.getStatusCode());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("output", runtimeResponse.getBody());
        return success(id, result);
    }

    private Map<String, Object> toolView(ControlMcpVisibilityEntity entity) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", entity.getTargetName());
        tool.put("description", StringUtils.hasText(entity.getNote()) ? entity.getNote() : entity.getTargetName());
        tool.put("inputSchema", Map.of(
                "type", "object",
                "properties", Map.of()));
        tool.put("metadata", Map.of(
                "targetKind", entity.getTargetKind()));
        return tool;
    }

    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of(
                "code", code,
                "message", StringUtils.hasText(message) ? message : "MCP request failed"));
        return response;
    }

    private void recordCall(ControlMcpClientEntity client,
                            String method,
                            Map<String, Object> request,
                            Map<String, Object> response,
                            boolean success,
                            long started,
                            String errorMessage) {
        ControlMcpCallLogEntity log = new ControlMcpCallLogEntity();
        log.setClientId(client.getId());
        log.setClientName(client.getName());
        log.setMethod(method);
        log.setSuccess(success);
        log.setLatencyMs((System.nanoTime() - started) / 1_000_000L);
        log.setRequestBody(toJson(request == null ? Map.of() : request));
        log.setResponseBody(toJson(response));
        log.setErrorMessage(errorMessage);
        log.setCreatedAt(LocalDateTime.now());
        callLogMapper.insert(log);
    }

    private List<String> parseStringList(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            if (parsed instanceof Collection<?> collection) {
                return collection.stream()
                        .map(String::valueOf)
                        .filter(StringUtils::hasText)
                        .toList();
            }
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("MCP payload json is invalid", ex);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
