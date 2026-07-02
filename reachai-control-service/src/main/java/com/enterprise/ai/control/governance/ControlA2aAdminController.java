package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/a2a")
@RequiredArgsConstructor
public class ControlA2aAdminController {

    private final ControlA2aEndpointMapper endpointMapper;
    private final ControlA2aCallLogMapper callLogMapper;
    private final RuntimeProxyClient runtimeClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/endpoints")
    public ResponseEntity<Page<ControlA2aEndpointEntity>> listEndpoints(@RequestParam(defaultValue = "1") int pageNum,
                                                                        @RequestParam(defaultValue = "20") int pageSize,
                                                                        @RequestParam(required = false) String agentKey,
                                                                        @RequestParam(required = false) Boolean enabled) {
        LambdaQueryWrapper<ControlA2aEndpointEntity> wrapper = new LambdaQueryWrapper<ControlA2aEndpointEntity>()
                .like(StringUtils.hasText(agentKey), ControlA2aEndpointEntity::getAgentKey, agentKey)
                .eq(enabled != null, ControlA2aEndpointEntity::getEnabled, enabled)
                .orderByDesc(ControlA2aEndpointEntity::getId);
        return ResponseEntity.ok(endpointMapper.selectPage(new Page<>(safePage(pageNum), safeSize(pageSize, 20, 500)), wrapper));
    }

    @GetMapping("/endpoints/{id}")
    public ResponseEntity<Map<String, Object>> getEndpoint(@PathVariable Long id) {
        ControlA2aEndpointEntity entity = endpointMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("entity", entity, "card", parseCard(entity.getCardJson())));
    }

    @PostMapping("/endpoints")
    public ResponseEntity<ControlA2aEndpointEntity> upsertEndpoint(@RequestBody Map<String, Object> request) {
        String agentId = requireText(stringValue(request == null ? null : request.get("agentId")), "agentId");
        Map<String, Object> agent = resolveRuntimeAgent(agentId);
        String resolvedAgentId = firstText(stringValue(agent.get("id")), agentId);
        String agentKey = firstText(stringValue(agent.get("keySlug")), stringValue(agent.get("key")));
        agentKey = firstText(agentKey, resolvedAgentId);

        ControlA2aEndpointEntity entity = endpointMapper.selectOne(new LambdaQueryWrapper<ControlA2aEndpointEntity>()
                .eq(ControlA2aEndpointEntity::getAgentId, resolvedAgentId)
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (entity == null) {
            entity = new ControlA2aEndpointEntity();
            entity.setAgentId(resolvedAgentId);
            entity.setCreatedAt(now);
        }
        entity.setAgentKey(agentKey);
        entity.setProjectId(longValue(agent.get("projectId")));
        entity.setProjectCode(stringValue(agent.get("projectCode")));
        entity.setEnvironment(stringValue(agent.get("environment")));
        entity.setTenantId(stringValue(agent.get("tenantId")));
        entity.setCardJson(toJson(buildCard(agent, request)));
        entity.setEnabled(request == null || !request.containsKey("enabled") || Boolean.TRUE.equals(request.get("enabled")));
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            endpointMapper.insert(entity);
        } else {
            endpointMapper.updateById(entity);
        }
        return ResponseEntity.ok(entity);
    }

    @PutMapping("/endpoints/{id}/enabled")
    public ResponseEntity<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        ControlA2aEndpointEntity entity = endpointMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        entity.setEnabled(enabled);
        entity.setUpdatedAt(LocalDateTime.now());
        endpointMapper.updateById(entity);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/endpoints/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        endpointMapper.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/call-logs")
    public ResponseEntity<Page<ControlA2aCallLogEntity>> listLogs(@RequestParam(defaultValue = "1") int pageNum,
                                                                  @RequestParam(defaultValue = "20") int pageSize,
                                                                  @RequestParam(required = false) String agentKey,
                                                                  @RequestParam(required = false) String method,
                                                                  @RequestParam(required = false) Boolean success) {
        LambdaQueryWrapper<ControlA2aCallLogEntity> wrapper = new LambdaQueryWrapper<ControlA2aCallLogEntity>()
                .like(StringUtils.hasText(agentKey), ControlA2aCallLogEntity::getAgentKey, agentKey)
                .eq(StringUtils.hasText(method), ControlA2aCallLogEntity::getMethod, method)
                .eq(success != null, ControlA2aCallLogEntity::getSuccess, success)
                .orderByDesc(ControlA2aCallLogEntity::getId);
        return ResponseEntity.ok(callLogMapper.selectPage(new Page<>(safePage(pageNum), safeSize(pageSize, 20, 500)), wrapper));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveRuntimeAgent(String agentId) {
        ResponseEntity<Object> response = runtimeClient.getAgent(agentId);
        Object body = response.getBody();
        if (body instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("runtime agent lookup did not return an object");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCard(Map<String, Object> agent, Map<String, Object> request) {
        Map<String, Object> card = new LinkedHashMap<>();
        String name = firstText(stringValue(agent.get("name")), stringValue(agent.get("keySlug")));
        card.put("name", firstText(name, stringValue(agent.get("id"))));
        card.put("description", stringValue(agent.get("description")));
        card.put("version", "1.0.0");
        card.put("protocolVersion", "0.2.0");
        card.put("capabilities", Map.of("streaming", false, "pushNotifications", false));
        card.put("skills", List.of());
        Object override = request == null ? null : request.get("card");
        if (override instanceof Map<?, ?> overrideMap) {
            card.putAll((Map<String, Object>) overrideMap);
        }
        return card;
    }

    private Map<String, Object> parseCard(String cardJson) {
        if (!StringUtils.hasText(cardJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(cardJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of("raw", cardJson);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("A2A card json is invalid", ex);
        }
    }

    private int safePage(int requested) {
        return Math.max(requested, 1);
    }

    private int safeSize(int requested, int fallback, int max) {
        int value = requested <= 0 ? fallback : requested;
        return Math.min(Math.max(value, 1), max);
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return Long.parseLong(String.valueOf(value));
        }
        return null;
    }
}
