package com.enterprise.ai.runtime.credential;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowCredentialService {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeWorkflowCredentialMapper mapper;
    private final RuntimeWorkflowCredentialCipher cipher;
    private final ObjectMapper objectMapper;

    public List<RuntimeWorkflowCredentialView> list(Long projectId, String projectCode) {
        return mapper.selectList(Wrappers.<RuntimeWorkflowCredentialEntity>lambdaQuery()
                        .eq(RuntimeWorkflowCredentialEntity::getStatus, "ACTIVE")
                        .and(projectId != null || StringUtils.hasText(projectCode), q -> {
                            if (projectId != null) {
                                q.eq(RuntimeWorkflowCredentialEntity::getProjectId, projectId).or()
                                        .eq(RuntimeWorkflowCredentialEntity::getScope, "GLOBAL");
                            }
                            if (StringUtils.hasText(projectCode)) {
                                q.eq(RuntimeWorkflowCredentialEntity::getProjectCode, projectCode.trim()).or()
                                        .eq(RuntimeWorkflowCredentialEntity::getScope, "GLOBAL");
                            }
                        })
                        .orderByAsc(RuntimeWorkflowCredentialEntity::getName))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public RuntimeWorkflowCredentialView create(RuntimeWorkflowCredentialRequest request) {
        RuntimeWorkflowCredentialEntity entity = new RuntimeWorkflowCredentialEntity();
        fill(entity, request, false);
        if (!StringUtils.hasText(entity.getCredentialRef())) {
            entity.setCredentialRef("cred_" + UUID.randomUUID().toString().replace("-", ""));
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return toView(entity);
    }

    @Transactional
    public RuntimeWorkflowCredentialView update(Long id, RuntimeWorkflowCredentialRequest request) {
        RuntimeWorkflowCredentialEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Credential not found: " + id);
        }
        fill(entity, request, true);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return toView(entity);
    }

    @Transactional
    public void delete(Long id) {
        RuntimeWorkflowCredentialEntity entity = mapper.selectById(id);
        if (entity == null) {
            return;
        }
        entity.setStatus("DISABLED");
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
    }

    public Optional<RuntimeWorkflowCredentialRuntime> resolve(String credentialRef, Long projectId, String projectCode) {
        if (!StringUtils.hasText(credentialRef)) {
            return Optional.empty();
        }
        RuntimeWorkflowCredentialEntity entity = mapper.selectOne(Wrappers.<RuntimeWorkflowCredentialEntity>lambdaQuery()
                .eq(RuntimeWorkflowCredentialEntity::getCredentialRef, credentialRef.trim())
                .eq(RuntimeWorkflowCredentialEntity::getStatus, "ACTIVE")
                .and(projectId != null || StringUtils.hasText(projectCode), q -> {
                    q.eq(RuntimeWorkflowCredentialEntity::getScope, "GLOBAL");
                    if (projectId != null) {
                        q.or().eq(RuntimeWorkflowCredentialEntity::getProjectId, projectId);
                    }
                    if (StringUtils.hasText(projectCode)) {
                        q.or().eq(RuntimeWorkflowCredentialEntity::getProjectCode, projectCode.trim());
                    }
                })
                .last("LIMIT 1"));
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeWorkflowCredentialRuntime(
                entity.getCredentialRef(),
                entity.getName(),
                entity.getType(),
                readSecret(entity)));
    }

    private void fill(RuntimeWorkflowCredentialEntity entity,
                      RuntimeWorkflowCredentialRequest request,
                      boolean preserveEmptySecret) {
        if (request == null) {
            throw new IllegalArgumentException("Credential request is required");
        }
        if (StringUtils.hasText(request.credentialRef())) {
            entity.setCredentialRef(request.credentialRef().trim());
        }
        entity.setName(required(request.name(), "name"));
        entity.setType(required(request.type(), "type").toUpperCase());
        entity.setProjectId(request.projectId());
        entity.setProjectCode(trimToNull(request.projectCode()));
        entity.setScope(StringUtils.hasText(request.scope()) ? request.scope().trim().toUpperCase() : "PROJECT");
        entity.setStatus(StringUtils.hasText(request.status()) ? request.status().trim().toUpperCase() : "ACTIVE");
        if (!preserveEmptySecret || request.secret() != null) {
            entity.setSecretJson(cipher.encrypt(toJson(request.secret() == null ? Map.of() : request.secret())));
        }
    }

    private RuntimeWorkflowCredentialView toView(RuntimeWorkflowCredentialEntity entity) {
        return new RuntimeWorkflowCredentialView(
                entity.getId(),
                entity.getCredentialRef(),
                entity.getName(),
                entity.getType(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getScope(),
                entity.getStatus(),
                mask(readSecret(entity)),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private Map<String, Object> readSecret(RuntimeWorkflowCredentialEntity entity) {
        try {
            String json = cipher.decrypt(entity.getSecretJson());
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Map<String, Object> mask(Map<String, Object> secret) {
        Map<String, Object> masked = new LinkedHashMap<>();
        secret.forEach((key, value) -> masked.put(key, maskValue(value)));
        return masked;
    }

    private Object maskValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            map.forEach((key, child) -> masked.put(String.valueOf(key), maskValue(child)));
            return masked;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::maskValue).toList();
        }
        String text = value == null ? "" : String.valueOf(value);
        if (text.isBlank()) {
            return "";
        }
        return text.length() <= 4 ? "****" : text.substring(0, 2) + "****" + text.substring(text.length() - 2);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid credential secret", ex);
        }
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Credential " + field + " is required");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
