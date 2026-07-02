package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class ControlMcpAdminController {

    private final ControlMcpClientMapper clientMapper;
    private final ControlMcpVisibilityMapper visibilityMapper;
    private final ControlMcpCallLogMapper callLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    @GetMapping("/clients")
    public ResponseEntity<List<ControlMcpClientEntity>> listClients() {
        return ResponseEntity.ok(clientMapper.selectList(new LambdaQueryWrapper<ControlMcpClientEntity>()
                .orderByDesc(ControlMcpClientEntity::getId)));
    }

    @PostMapping("/clients")
    public ResponseEntity<Map<String, Object>> createClient(@RequestBody CreateClientRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("MCP client request is required");
        }
        String plaintextApiKey = generateApiKey();
        LocalDateTime now = LocalDateTime.now();
        ControlMcpClientEntity entity = new ControlMcpClientEntity();
        entity.setName(requireText(request.name(), "name"));
        entity.setApiKeyPrefix(plaintextApiKey.substring(0, Math.min(16, plaintextApiKey.length())));
        entity.setApiKeyHash(sha256(plaintextApiKey));
        entity.setRolesJson(toJson(request.roles() == null ? List.of() : request.roles()));
        entity.setToolWhitelistJson(toJson(request.toolWhitelist() == null ? List.of() : request.toolWhitelist()));
        entity.setEnabled(true);
        entity.setExpiresAt(request.expiresAt());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        clientMapper.insert(entity);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("plaintextApiKey", plaintextApiKey);
        response.put("client", entity);
        response.put("note", "Store this API key now. It will not be shown again.");
        return ResponseEntity.ok(response);
    }

    @PutMapping("/clients/{id}")
    public ResponseEntity<ControlMcpClientEntity> updateClient(@PathVariable Long id,
                                                               @RequestBody UpdateClientRequest request) {
        ControlMcpClientEntity entity = clientMapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        if (StringUtils.hasText(request.name())) {
            entity.setName(request.name().trim());
        }
        if (request.roles() != null) {
            entity.setRolesJson(toJson(request.roles()));
        }
        if (request.toolWhitelist() != null) {
            entity.setToolWhitelistJson(toJson(request.toolWhitelist()));
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        entity.setExpiresAt(request.expiresAt());
        entity.setUpdatedAt(LocalDateTime.now());
        clientMapper.updateById(entity);
        return ResponseEntity.ok(entity);
    }

    @DeleteMapping("/clients/{id}")
    public ResponseEntity<Map<String, Object>> deleteClient(@PathVariable Long id) {
        clientMapper.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/visibility")
    public ResponseEntity<List<ControlMcpVisibilityEntity>> listVisibility() {
        return ResponseEntity.ok(visibilityMapper.selectList(new LambdaQueryWrapper<ControlMcpVisibilityEntity>()
                .orderByAsc(ControlMcpVisibilityEntity::getTargetKind)
                .orderByAsc(ControlMcpVisibilityEntity::getTargetName)));
    }

    @PostMapping("/visibility")
    public ResponseEntity<ControlMcpVisibilityEntity> setVisibility(@RequestBody SetVisibilityRequest request) {
        String targetKind = targetKind(request == null ? null : firstText(request.kind(), request.targetKind()));
        String targetName = requireText(request == null ? null : firstText(request.name(), request.targetName()), "name");
        ControlMcpVisibilityEntity entity = visibilityMapper.selectOne(new LambdaQueryWrapper<ControlMcpVisibilityEntity>()
                .eq(ControlMcpVisibilityEntity::getTargetKind, targetKind)
                .eq(ControlMcpVisibilityEntity::getTargetName, targetName)
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (entity == null) {
            entity = new ControlMcpVisibilityEntity();
            entity.setTargetKind(targetKind);
            entity.setTargetName(targetName);
            entity.setCreatedAt(now);
        }
        entity.setExposed(request == null || Boolean.TRUE.equals(request.exposed()));
        entity.setNote(trimToNull(request == null ? null : request.note()));
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            visibilityMapper.insert(entity);
        } else {
            visibilityMapper.updateById(entity);
        }
        return ResponseEntity.ok(entity);
    }

    @GetMapping("/call-logs")
    public ResponseEntity<Page<ControlMcpCallLogEntity>> pageLogs(@RequestParam(defaultValue = "1") int current,
                                                                  @RequestParam(defaultValue = "50") int size,
                                                                  @RequestParam(required = false) Long clientId,
                                                                  @RequestParam(required = false) String method,
                                                                  @RequestParam(required = false) Boolean success,
                                                                  @RequestParam(required = false) Integer days) {
        LambdaQueryWrapper<ControlMcpCallLogEntity> wrapper = new LambdaQueryWrapper<ControlMcpCallLogEntity>()
                .eq(clientId != null, ControlMcpCallLogEntity::getClientId, clientId)
                .eq(StringUtils.hasText(method), ControlMcpCallLogEntity::getMethod, method)
                .eq(success != null, ControlMcpCallLogEntity::getSuccess, success)
                .ge(days != null && days > 0, ControlMcpCallLogEntity::getCreatedAt,
                        days == null ? null : LocalDateTime.now().minusDays(days))
                .orderByDesc(ControlMcpCallLogEntity::getId);
        return ResponseEntity.ok(callLogMapper.selectPage(new Page<>(safePage(current), safeSize(size, 50, 500)), wrapper));
    }

    private String generateApiKey() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return "mcp_" + HexFormat.of().formatHex(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("MCP payload json is invalid", ex);
        }
    }

    private String targetKind(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "TOOL";
        if (!List.of("TOOL", "SKILL").contains(normalized)) {
            throw new IllegalArgumentException("target kind is invalid");
        }
        return normalized;
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

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record CreateClientRequest(String name,
                                      List<String> roles,
                                      List<String> toolWhitelist,
                                      LocalDateTime expiresAt) {
    }

    public record UpdateClientRequest(String name,
                                      List<String> roles,
                                      List<String> toolWhitelist,
                                      Boolean enabled,
                                      LocalDateTime expiresAt) {
    }

    public record SetVisibilityRequest(String kind,
                                       String name,
                                       Boolean exposed,
                                       String note,
                                       String targetKind,
                                       String targetName) {
        public SetVisibilityRequest(String kind, String name, Boolean exposed, String note) {
            this(kind, name, exposed, note, null, null);
        }
    }
}
