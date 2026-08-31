package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItem;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MybatisMcpPublicationRepository implements McpPublicationRepository {

    private static final TypeReference<List<McpToolProjection>> TOOL_LIST = new TypeReference<>() { };

    private final McpPublicationMapper publicationMapper;
    private final McpPublicationItemMapper itemMapper;
    private final McpPublicationRevisionMapper revisionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<McpPublication> findById(long id) {
        return Optional.ofNullable(publicationMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<McpPublication> findByName(String name) {
        return Optional.ofNullable(publicationMapper.selectOne(
                        Wrappers.<McpPublicationEntity>lambdaQuery()
                                .eq(McpPublicationEntity::getName, name)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public boolean existsByName(String name) {
        return publicationMapper.selectCount(Wrappers.<McpPublicationEntity>lambdaQuery()
                .eq(McpPublicationEntity::getName, name)) > 0;
    }

    @Override
    public Page findPage(String search, String state, int limit, int offset) {
        String normalizedSearch = trimToNull(search);
        String normalizedState = trimToNull(state);
        var query = Wrappers.<McpPublicationEntity>lambdaQuery()
                .eq(normalizedState != null, McpPublicationEntity::getState, normalizedState)
                .and(normalizedSearch != null, nested -> nested
                        .like(McpPublicationEntity::getName, normalizedSearch)
                        .or()
                        .like(McpPublicationEntity::getDescription, normalizedSearch))
                .orderByDesc(McpPublicationEntity::getUpdatedAt)
                .orderByDesc(McpPublicationEntity::getId);
        long total = publicationMapper.selectCount(query);
        query.last("LIMIT " + limit + " OFFSET " + offset);
        return new Page(publicationMapper.selectList(query).stream().map(this::toDomain).toList(), total);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public McpPublication save(McpPublication publication) {
        McpPublicationEntity entity = toEntity(publication);
        try {
            if (entity.getId() == null) {
                publicationMapper.insert(entity);
            } else if (publicationMapper.updateById(entity) != 1) {
                throw new McpDomainException("MCP_PUBLICATION_SAVE_FAILED",
                        "publication update did not match any row: " + entity.getId());
            }
        } catch (DuplicateKeyException exception) {
            throw new McpDomainException("MCP_PUBLICATION_NAME_CONFLICT",
                    "publication name is already in use: " + publication.name());
        }
        McpPublicationEntity reloaded = publicationMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw new McpDomainException("MCP_PUBLICATION_PERSISTENCE_FAILED",
                    "publication could not be reloaded after persistence");
        }
        return toDomain(reloaded);
    }

    @Override
    public List<McpPublicationItem> findItems(long publicationId) {
        return itemMapper.selectList(Wrappers.<McpPublicationItemEntity>lambdaQuery()
                        .eq(McpPublicationItemEntity::getPublicationId, publicationId)
                        .orderByAsc(McpPublicationItemEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public McpPublicationItem saveItem(McpPublicationItem item) {
        McpPublicationItemEntity entity = toEntity(item);
        try {
            if (entity.getId() == null) {
                itemMapper.insert(entity);
            } else if (itemMapper.updateById(entity) != 1) {
                throw new McpDomainException("MCP_PUBLICATION_ITEM_SAVE_FAILED",
                        "publication item update did not match any row: " + entity.getId());
            }
        } catch (DuplicateKeyException exception) {
            throw new McpDomainException("MCP_PUBLICATION_ITEM_CONFLICT",
                    "the publication already contains this source");
        }
        McpPublicationItemEntity reloaded = itemMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw new McpDomainException("MCP_PUBLICATION_ITEM_PERSISTENCE_FAILED",
                    "publication item could not be reloaded after persistence");
        }
        return toDomain(reloaded);
    }

    @Override
    public boolean deleteItem(long publicationId, long itemId) {
        return itemMapper.delete(Wrappers.<McpPublicationItemEntity>lambdaQuery()
                .eq(McpPublicationItemEntity::getId, itemId)
                .eq(McpPublicationItemEntity::getPublicationId, publicationId)) > 0;
    }

    @Override
    public int nextRevisionNo(long publicationId) {
        McpPublicationRevisionEntity latest = revisionMapper.selectOne(
                Wrappers.<McpPublicationRevisionEntity>lambdaQuery()
                        .select(McpPublicationRevisionEntity::getRevisionNo)
                        .eq(McpPublicationRevisionEntity::getPublicationId, publicationId)
                        .orderByDesc(McpPublicationRevisionEntity::getRevisionNo)
                        .last("LIMIT 1"));
        return latest == null || latest.getRevisionNo() == null ? 1 : latest.getRevisionNo() + 1;
    }

    @Override
    public McpPublicationRevision saveRevision(McpPublicationRevision revision) {
        if (revision.id() != null) {
            throw new McpDomainException("MCP_REVISION_IMMUTABLE",
                    "a published revision is immutable and cannot be updated");
        }
        McpPublicationRevisionEntity entity = toEntity(revision);
        try {
            revisionMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new McpDomainException("MCP_REVISION_CONFLICT",
                    "the revision number already exists for this publication");
        }
        McpPublicationRevisionEntity reloaded = revisionMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw new McpDomainException("MCP_REVISION_PERSISTENCE_FAILED",
                    "publication revision could not be reloaded after persistence");
        }
        return toDomain(reloaded);
    }

    @Override
    public Optional<McpPublicationRevision> findRevision(long publicationId, long revisionId) {
        return Optional.ofNullable(revisionMapper.selectOne(
                        Wrappers.<McpPublicationRevisionEntity>lambdaQuery()
                                .eq(McpPublicationRevisionEntity::getPublicationId, publicationId)
                                .eq(McpPublicationRevisionEntity::getId, revisionId)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public List<McpPublicationRevision> findRevisions(long publicationId) {
        return revisionMapper.selectList(Wrappers.<McpPublicationRevisionEntity>lambdaQuery()
                        .eq(McpPublicationRevisionEntity::getPublicationId, publicationId)
                        .orderByDesc(McpPublicationRevisionEntity::getRevisionNo))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private McpPublication toDomain(McpPublicationEntity entity) {
        return new McpPublication(
                entity.getId(), entity.getName(), entity.getDescription(),
                McpPublicationStatus.parse(entity.getState()), entity.getCurrentRevisionId(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private McpPublicationEntity toEntity(McpPublication publication) {
        McpPublicationEntity entity = new McpPublicationEntity();
        entity.setId(publication.id());
        entity.setName(publication.name());
        entity.setDescription(publication.description());
        entity.setState(publication.state().name());
        entity.setCurrentRevisionId(publication.currentRevisionId());
        entity.setCreatedAt(publication.createdAt());
        entity.setUpdatedAt(publication.updatedAt());
        return entity;
    }

    private McpPublicationItem toDomain(McpPublicationItemEntity entity) {
        return new McpPublicationItem(
                entity.getId(), entity.getPublicationId(),
                McpPublicationItemKind.parse(entity.getSourceKind()), entity.getSourceRef(),
                entity.getAlias(), entity.getDescriptionOverride(), entity.getRiskLevelOverride(),
                !Boolean.FALSE.equals(entity.getEnabled()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private McpPublicationItemEntity toEntity(McpPublicationItem item) {
        McpPublicationItemEntity entity = new McpPublicationItemEntity();
        entity.setId(item.id());
        entity.setPublicationId(item.publicationId());
        entity.setSourceKind(item.sourceKind().name());
        entity.setSourceRef(item.sourceRef());
        entity.setAlias(item.alias());
        entity.setDescriptionOverride(item.descriptionOverride());
        entity.setRiskLevelOverride(item.riskLevelOverride());
        entity.setEnabled(item.enabled());
        entity.setCreatedAt(item.createdAt());
        entity.setUpdatedAt(item.updatedAt());
        return entity;
    }

    private McpPublicationRevision toDomain(McpPublicationRevisionEntity entity) {
        return new McpPublicationRevision(
                entity.getId(), entity.getPublicationId(),
                entity.getRevisionNo() == null ? 0 : entity.getRevisionNo(),
                read(entity.getToolsSnapshotJson(), TOOL_LIST, "toolsSnapshotJson"),
                entity.getRiskSummaryJson(), entity.getPublishedAt());
    }

    private McpPublicationRevisionEntity toEntity(McpPublicationRevision revision) {
        McpPublicationRevisionEntity entity = new McpPublicationRevisionEntity();
        entity.setPublicationId(revision.publicationId());
        entity.setRevisionNo(revision.revisionNo());
        entity.setToolsSnapshotJson(write(revision.tools(), "toolsSnapshotJson"));
        entity.setRiskSummaryJson(revision.riskSummaryJson());
        entity.setPublishedAt(revision.publishedAt());
        return entity;
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            McpDomainException error = new McpDomainException("MCP_REVISION_DATA_INVALID",
                    "persisted " + field + " is invalid");
            error.initCause(exception);
            throw error;
        }
    }

    private String write(Object value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            McpDomainException error = new McpDomainException("MCP_REVISION_DATA_INVALID",
                    field + " could not be serialized");
            error.initCause(exception);
            throw error;
        }
    }
}
