package com.enterprise.ai.agent.platform.control.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlatformAuthProviderConfigService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PlatformAuthProviderConfigMapper providerMapper;

    public List<ProviderView> listProviders() {
        return providerMapper.selectList(Wrappers.<PlatformAuthProviderConfigEntity>lambdaQuery()
                        .orderByAsc(PlatformAuthProviderConfigEntity::getProviderCode))
                .stream()
                .map(this::toView)
                .toList();
    }

    public RuntimeProviderConfig loadActiveProvider(String providerCode) {
        if (!StringUtils.hasText(providerCode)) {
            throw new IllegalArgumentException("providerCode is required");
        }
        PlatformAuthProviderConfigEntity entity = providerMapper.selectOne(Wrappers.<PlatformAuthProviderConfigEntity>lambdaQuery()
                .eq(PlatformAuthProviderConfigEntity::getProviderCode, providerCode.trim().toUpperCase(Locale.ROOT))
                .eq(PlatformAuthProviderConfigEntity::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (entity == null) {
            throw new IllegalArgumentException("active auth provider is not configured: " + providerCode);
        }
        return new RuntimeProviderConfig(
                entity.getProviderCode(),
                entity.getProviderType(),
                parseConfig(entity.getConfigJson()));
    }

    @Transactional
    public ProviderView saveProvider(ProviderCommand command) {
        if (command == null || !StringUtils.hasText(command.providerCode()) || !StringUtils.hasText(command.providerType())) {
            throw new IllegalArgumentException("providerCode and providerType are required");
        }
        LocalDateTime now = LocalDateTime.now();
        String providerCode = command.providerCode().trim().toUpperCase(Locale.ROOT);
        PlatformAuthProviderConfigEntity entity = providerMapper.selectOne(Wrappers.<PlatformAuthProviderConfigEntity>lambdaQuery()
                .eq(PlatformAuthProviderConfigEntity::getProviderCode, providerCode)
                .last("LIMIT 1"));
        if (entity == null) {
            entity = new PlatformAuthProviderConfigEntity();
            entity.setProviderCode(providerCode);
            entity.setCreatedAt(now);
        }
        entity.setProviderName(StringUtils.hasText(command.providerName()) ? command.providerName().trim() : providerCode);
        entity.setProviderType(command.providerType().trim().toUpperCase(Locale.ROOT));
        entity.setStatus(StringUtils.hasText(command.status()) ? command.status().trim().toUpperCase(Locale.ROOT) : "ACTIVE");
        entity.setConfigJson(StringUtils.hasText(command.configJson()) ? command.configJson().trim() : "{}");
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            providerMapper.insert(entity);
        } else {
            providerMapper.updateById(entity);
        }
        return toView(entity);
    }

    private ProviderView toView(PlatformAuthProviderConfigEntity entity) {
        return new ProviderView(
                entity.getId(),
                entity.getProviderCode(),
                entity.getProviderName(),
                entity.getProviderType(),
                entity.getStatus(),
                maskSecrets(entity.getConfigJson()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String maskSecrets(String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return "{}";
        }
        try {
            Map<String, Object> value = OBJECT_MAPPER.readValue(configJson, new TypeReference<>() {
            });
            maskMap(value);
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return configJson;
        }
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(configJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("auth provider configJson is invalid", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private void maskMap(Map<String, Object> value) {
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (isSensitiveKey(entry.getKey())) {
                entry.setValue("******");
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                maskMap((Map<String, Object>) nested);
            } else if (entry.getValue() instanceof List<?> list) {
                list.stream()
                        .filter(item -> item instanceof Map<?, ?>)
                        .forEach(item -> maskMap((Map<String, Object>) item));
            }
        }
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("privatekey")
                || normalized.contains("private_key");
    }

    public record ProviderView(Long id,
                               String providerCode,
                               String providerName,
                               String providerType,
                               String status,
                               String configJson,
                               LocalDateTime createdAt,
                               LocalDateTime updatedAt) {
    }

    public record ProviderCommand(String providerCode,
                                  String providerName,
                                  String providerType,
                                  String status,
                                  String configJson) {
    }

    public record RuntimeProviderConfig(String providerCode,
                                        String providerType,
                                        Map<String, Object> config) {
        public RuntimeProviderConfig {
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }
}
