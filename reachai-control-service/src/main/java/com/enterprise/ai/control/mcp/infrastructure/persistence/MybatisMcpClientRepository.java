package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.port.McpClientRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import com.enterprise.ai.control.mcp.domain.client.McpClientStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MybatisMcpClientRepository implements McpClientRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final McpClientMapper clientMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<McpClient> findById(long id) {
        return Optional.ofNullable(clientMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<McpClient> findByApiKeyHash(String apiKeyHash) {
        return Optional.ofNullable(clientMapper.selectOne(
                        Wrappers.<McpClientEntity>lambdaQuery()
                                .eq(McpClientEntity::getApiKeyHash, apiKeyHash)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public List<McpClient> findByPublication(long publicationId) {
        return clientMapper.selectList(Wrappers.<McpClientEntity>lambdaQuery()
                        .eq(McpClientEntity::getPublicationId, publicationId)
                        .orderByDesc(McpClientEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public McpClient save(McpClient client) {
        McpClientEntity entity = toEntity(client);
        try {
            if (entity.getId() == null) {
                clientMapper.insert(entity);
            } else if (clientMapper.updateById(entity) != 1) {
                throw new McpDomainException("MCP_CLIENT_SAVE_FAILED",
                        "client update did not match any row: " + entity.getId());
            }
        } catch (DuplicateKeyException exception) {
            throw new McpDomainException("MCP_CLIENT_CONFLICT",
                    "the credential hash is already in use");
        }
        McpClientEntity reloaded = clientMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw new McpDomainException("MCP_CLIENT_PERSISTENCE_FAILED",
                    "client could not be reloaded after persistence");
        }
        return toDomain(reloaded);
    }

    @Override
    public void touchLastUsed(long clientId, LocalDateTime now) {
        clientMapper.update(null, Wrappers.<McpClientEntity>lambdaUpdate()
                .eq(McpClientEntity::getId, clientId)
                .set(McpClientEntity::getLastUsedAt, now));
    }

    private McpClient toDomain(McpClientEntity entity) {
        return new McpClient(
                entity.getId(), entity.getPublicationId(), entity.getName(),
                entity.getProjectId(), entity.getProjectCode(), entity.getEnvironment(), entity.getTenantId(),
                entity.getApiKeyPrefix(), entity.getApiKeyHash(),
                read(entity.getRolesJson(), "rolesJson"),
                read(entity.getToolScopeJson(), "toolScopeJson"),
                McpClientStatus.parse(entity.getState()),
                !Boolean.FALSE.equals(entity.getEnabled()),
                entity.getExpiresAt(), entity.getLastUsedAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private McpClientEntity toEntity(McpClient client) {
        McpClientEntity entity = new McpClientEntity();
        entity.setId(client.id());
        entity.setPublicationId(client.publicationId());
        entity.setName(client.name());
        entity.setProjectId(client.projectId());
        entity.setProjectCode(client.projectCode());
        entity.setEnvironment(client.environment());
        entity.setTenantId(client.tenantId());
        entity.setApiKeyPrefix(client.apiKeyPrefix());
        entity.setApiKeyHash(client.apiKeyHash());
        entity.setRolesJson(write(client.roles(), "rolesJson"));
        entity.setToolScopeJson(client.toolScope().isEmpty() ? null
                : write(client.toolScope(), "toolScopeJson"));
        entity.setState(client.state().name());
        entity.setEnabled(client.enabled());
        entity.setExpiresAt(client.expiresAt());
        entity.setLastUsedAt(client.lastUsedAt());
        entity.setCreatedAt(client.createdAt());
        entity.setUpdatedAt(client.updatedAt());
        return entity;
    }

    private List<String> read(String json, String field) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            McpDomainException error = new McpDomainException("MCP_CLIENT_DATA_INVALID",
                    "persisted " + field + " is invalid");
            error.initCause(exception);
            throw error;
        }
    }

    private String write(List<String> value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            McpDomainException error = new McpDomainException("MCP_CLIENT_DATA_INVALID",
                    field + " could not be serialized");
            error.initCause(exception);
            throw error;
        }
    }
}
