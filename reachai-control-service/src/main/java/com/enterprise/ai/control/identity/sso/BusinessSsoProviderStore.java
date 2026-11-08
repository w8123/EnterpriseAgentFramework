package com.enterprise.ai.control.identity.sso;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.control.identity.PlatformAuthProviderEntity;
import com.enterprise.ai.control.identity.PlatformAuthProviderMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and writes business-system login entries in control_platform_auth_provider; config lives in config_json. */
@Component
@RequiredArgsConstructor
public class BusinessSsoProviderStore {

    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";

    private final PlatformAuthProviderMapper mapper;
    private final ObjectMapper objectMapper;

    public List<BusinessSsoProvider> list() {
        return mapper.selectList(new LambdaQueryWrapper<PlatformAuthProviderEntity>()
                        .eq(PlatformAuthProviderEntity::getProviderType, BusinessSsoProvider.TYPE)
                        .orderByAsc(PlatformAuthProviderEntity::getId))
                .stream().map(this::toProvider).toList();
    }

    public Optional<BusinessSsoProvider> find(String code) {
        PlatformAuthProviderEntity entity = entity(code);
        return entity == null ? Optional.empty() : Optional.of(toProvider(entity));
    }

    public BusinessSsoProvider save(BusinessSsoProvider provider) {
        PlatformAuthProviderEntity entity = entity(provider.code());
        LocalDateTime now = LocalDateTime.now();
        if (entity == null) {
            entity = new PlatformAuthProviderEntity();
            entity.setProviderCode(provider.code());
            entity.setCreatedAt(now);
        } else if (!BusinessSsoProvider.TYPE.equals(entity.getProviderType())) {
            throw new IllegalArgumentException("这个编码已被其他登录方式占用");
        }
        entity.setProviderType(BusinessSsoProvider.TYPE);
        entity.setProviderName(provider.label());
        entity.setStatus(provider.active() ? ACTIVE : INACTIVE);
        entity.setConfigJson(toJson(provider));
        entity.setUpdatedAt(now);
        if (entity.getId() == null) mapper.insert(entity);
        else mapper.updateById(entity);
        return toProvider(entity);
    }

    private PlatformAuthProviderEntity entity(String code) {
        if (code == null || code.isBlank()) return null;
        PlatformAuthProviderEntity entity = mapper.selectOne(new LambdaQueryWrapper<PlatformAuthProviderEntity>()
                .eq(PlatformAuthProviderEntity::getProviderCode, code.trim()).last("limit 1"));
        // A case-insensitive collation must not let "YUDAO" read the "yudao" entry.
        return entity != null && code.trim().equals(entity.getProviderCode()) ? entity : null;
    }

    private BusinessSsoProvider toProvider(PlatformAuthProviderEntity entity) {
        Map<String, Object> config = parse(entity.getConfigJson());
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.get("extraHeaders") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> {
                if (key != null && value != null) headers.put(String.valueOf(key), String.valueOf(value));
            });
        }
        return new BusinessSsoProvider(entity.getProviderCode(), entity.getProviderName(),
                text(config, "projectCode"), text(config, "authorizeUrl"), text(config, "tokenUrl"),
                text(config, "userInfoUrl"), text(config, "clientId"), text(config, "clientSecret"),
                text(config, "scope"), text(config, "userIdPath"), text(config, "userNamePath"), headers,
                BusinessSsoProvider.TYPE.equals(entity.getProviderType()) && ACTIVE.equalsIgnoreCase(entity.getStatus()));
    }

    private String toJson(BusinessSsoProvider provider) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("projectCode", provider.projectCode());
        config.put("authorizeUrl", provider.authorizeUrl());
        config.put("tokenUrl", provider.tokenUrl());
        config.put("userInfoUrl", provider.userInfoUrl());
        config.put("clientId", provider.clientId());
        config.put("clientSecret", provider.clientSecretCipher());
        config.put("scope", provider.scope());
        config.put("userIdPath", provider.userIdPath());
        config.put("userNamePath", provider.userNamePath());
        config.put("extraHeaders", provider.extraHeaders());
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("登录配置无法保存");
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException failure) {
            return Map.of();
        }
    }

    private static String text(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
