package com.enterprise.ai.agent.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP Client 凭证 CRUD + API Key 校验。
 * <p>
 * 安全要求：API Key 只在 {@code create} 接口返回一次，DB 仅保存 SHA-256 哈希。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpClientService {

    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final McpClientMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建 Client 并返回明文 API Key（一次性显示）。
     */
    public CreateResult create(String name, List<String> roles, List<String> toolWhitelist, LocalDateTime expiresAt) {
        String apiKey = generateApiKey();
        String hash = sha256Hex(apiKey);
        McpClientEntity row = new McpClientEntity();
        row.setName(name);
        row.setApiKeyHash(hash);
        row.setApiKeyPrefix(apiKey.substring(0, 8));
        row.setRolesJson(toJson(roles));
        row.setToolWhitelistJson(toJson(toolWhitelist));
        row.setEnabled(true);
        row.setExpiresAt(expiresAt);
        mapper.insert(row);
        return new CreateResult(row.getId(), apiKey, row);
    }

    public Optional<McpClientEntity> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();
        String hash = sha256Hex(apiKey.trim());
        LambdaQueryWrapper<McpClientEntity> w = new LambdaQueryWrapper<>();
        w.eq(McpClientEntity::getApiKeyHash, hash).last("LIMIT 1");
        McpClientEntity row = mapper.selectOne(w);
        if (row == null) return Optional.empty();
        if (Boolean.FALSE.equals(row.getEnabled())) return Optional.empty();
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(LocalDateTime.now())) return Optional.empty();
        // 异步更新 last_used_at；写失败也不影响校验通过
        try {
            row.setLastUsedAt(LocalDateTime.now());
            mapper.updateById(row);
        } catch (Exception ignored) { /* ignore */ }
        return Optional.of(row);
    }

    public List<McpClientEntity> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<McpClientEntity>()
                .orderByDesc(McpClientEntity::getCreatedAt));
    }

    public McpClientEntity update(Long id, String name, List<String> roles, List<String> toolWhitelist,
                                  Boolean enabled, LocalDateTime expiresAt) {
        McpClientEntity exist = mapper.selectById(id);
        if (exist == null) throw new IllegalArgumentException("MCP Client 不存在: " + id);
        if (name != null) exist.setName(name);
        if (roles != null) exist.setRolesJson(toJson(roles));
        if (toolWhitelist != null) exist.setToolWhitelistJson(toJson(toolWhitelist));
        if (enabled != null) exist.setEnabled(enabled);
        if (expiresAt != null) exist.setExpiresAt(expiresAt);
        mapper.updateById(exist);
        return exist;
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }

    public List<String> rolesOf(McpClientEntity client) {
        if (client == null || client.getRolesJson() == null) return List.of();
        return parseList(client.getRolesJson());
    }

    public List<String> toolWhitelistOf(McpClientEntity client) {
        if (client == null || client.getToolWhitelistJson() == null) return List.of();
        return parseList(client.getToolWhitelistJson());
    }

    private List<String> parseList(String json) {
        try {
            return objectMapper.readValue(json, STR_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception ex) {
            return "[]";
        }
    }

    public static String generateApiKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "eaf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public record CreateResult(Long id, String plaintextApiKey, McpClientEntity client) {}
}
