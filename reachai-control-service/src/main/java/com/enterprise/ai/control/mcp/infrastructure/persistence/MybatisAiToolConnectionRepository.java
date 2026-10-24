package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.port.AiToolConnectionRepository;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection.ConnectedSystem;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class MybatisAiToolConnectionRepository implements AiToolConnectionRepository {

    private final AiToolConnectionMapper connectionMapper;
    private final AiToolConnectionSystemMapper systemMapper;

    @Override
    @Transactional
    public AiToolConnection create(AiToolConnection connection, String keyHash) {
        LocalDateTime now = LocalDateTime.now();
        AiToolConnectionEntity entity = new AiToolConnectionEntity();
        entity.setTenantId(connection.tenantId());
        entity.setBusinessUserId(connection.businessUserId());
        entity.setAppId(connection.appId());
        entity.setExternalUserId(connection.externalUserId());
        entity.setUserName(connection.userName());
        entity.setToolKind(connection.toolKind().name());
        entity.setKeyPrefix(connection.keyPrefix());
        entity.setKeyHash(keyHash);
        entity.setState(connection.state().name());
        entity.setExpiresAt(connection.expiresAt());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        connectionMapper.insert(entity);
        for (ConnectedSystem system : connection.systems()) {
            AiToolConnectionSystemEntity row = new AiToolConnectionSystemEntity();
            row.setConnectionId(entity.getId());
            row.setProjectCode(system.projectCode());
            row.setPublicationId(system.publicationId());
            row.setCreatedAt(now);
            systemMapper.insert(row);
        }
        return toDomain(entity, connection.systems());
    }

    @Override
    public Optional<AiToolConnection> findById(long id) {
        return Optional.ofNullable(connectionMapper.selectById(id)).map(this::withSystems);
    }

    @Override
    public Optional<AiToolConnection> findByKeyHash(String keyHash) {
        return Optional.ofNullable(connectionMapper.selectOne(Wrappers.<AiToolConnectionEntity>lambdaQuery()
                        .eq(AiToolConnectionEntity::getKeyHash, keyHash)
                        .last("LIMIT 1")))
                .map(this::withSystems);
    }

    @Override
    public List<AiToolConnection> findByOwner(String tenantId, long businessUserId) {
        List<AiToolConnectionEntity> rows = connectionMapper.selectList(Wrappers.<AiToolConnectionEntity>lambdaQuery()
                .eq(AiToolConnectionEntity::getTenantId, tenantId)
                .eq(AiToolConnectionEntity::getBusinessUserId, businessUserId)
                .orderByDesc(AiToolConnectionEntity::getId)
                .last("LIMIT 50"));
        return withSystems(rows);
    }

    private List<AiToolConnection> withSystems(List<AiToolConnectionEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ConnectedSystem>> systems = systemMapper.selectList(
                        Wrappers.<AiToolConnectionSystemEntity>lambdaQuery()
                                .in(AiToolConnectionSystemEntity::getConnectionId,
                                        rows.stream().map(AiToolConnectionEntity::getId).toList())
                                .orderByAsc(AiToolConnectionSystemEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(AiToolConnectionSystemEntity::getConnectionId,
                        Collectors.mapping(this::toSystem, Collectors.toList())));
        return rows.stream().map(row -> toDomain(row, systems.getOrDefault(row.getId(), List.of()))).toList();
    }

    @Override
    public SearchPage search(SearchQuery query, LocalDateTime now) {
        var wrapper = Wrappers.<AiToolConnectionEntity>lambdaQuery();
        if (StringUtils.hasText(query.projectCode())) {
            wrapper.apply("id IN (SELECT connection_id FROM control_ai_tool_connection_system WHERE project_code = {0})",
                    query.projectCode().trim());
        }
        if (StringUtils.hasText(query.keyword())) {
            String keyword = query.keyword().trim();
            wrapper.and(q -> q.like(AiToolConnectionEntity::getUserName, keyword)
                    .or().eq(AiToolConnectionEntity::getExternalUserId, keyword));
        }
        String state = StringUtils.hasText(query.state()) ? query.state().trim().toUpperCase(Locale.ROOT) : null;
        if (AiToolConnectionStatus.ACTIVE.name().equals(state)) {
            wrapper.eq(AiToolConnectionEntity::getState, state)
                    .and(q -> q.isNull(AiToolConnectionEntity::getExpiresAt)
                            .or().gt(AiToolConnectionEntity::getExpiresAt, now));
        } else if (AiToolConnectionStatus.EXPIRED.name().equals(state)) {
            wrapper.and(q -> q.eq(AiToolConnectionEntity::getState, state)
                    .or(active -> active.eq(AiToolConnectionEntity::getState, AiToolConnectionStatus.ACTIVE.name())
                            .le(AiToolConnectionEntity::getExpiresAt, now)));
        } else if (state != null) {
            wrapper.eq(AiToolConnectionEntity::getState, state);
        }
        wrapper.orderByDesc(AiToolConnectionEntity::getId);
        long total = connectionMapper.selectCount(wrapper);
        wrapper.last("LIMIT " + query.limit() + " OFFSET " + query.offset());
        return new SearchPage(withSystems(connectionMapper.selectList(wrapper)), total);
    }

    @Override
    public long countActiveByOwner(String tenantId, long businessUserId, LocalDateTime now) {
        return connectionMapper.selectCount(Wrappers.<AiToolConnectionEntity>lambdaQuery()
                .eq(AiToolConnectionEntity::getTenantId, tenantId)
                .eq(AiToolConnectionEntity::getBusinessUserId, businessUserId)
                .eq(AiToolConnectionEntity::getState, AiToolConnectionStatus.ACTIVE.name())
                .and(q -> q.isNull(AiToolConnectionEntity::getExpiresAt)
                        .or().gt(AiToolConnectionEntity::getExpiresAt, now)));
    }

    @Override
    public void revoke(long id, LocalDateTime now) {
        terminal(id, AiToolConnectionStatus.REVOKED, now);
    }

    @Override
    public void expire(long id, LocalDateTime now) {
        terminal(id, AiToolConnectionStatus.EXPIRED, now);
    }

    @Override
    public void touchLastUsed(long id, LocalDateTime now) {
        connectionMapper.update(null, Wrappers.<AiToolConnectionEntity>lambdaUpdate()
                .eq(AiToolConnectionEntity::getId, id)
                .set(AiToolConnectionEntity::getLastUsedAt, now));
    }

    /** Only an ACTIVE row moves to a terminal state; terminal states never change again. */
    private void terminal(long id, AiToolConnectionStatus next, LocalDateTime now) {
        connectionMapper.update(null, Wrappers.<AiToolConnectionEntity>lambdaUpdate()
                .eq(AiToolConnectionEntity::getId, id)
                .eq(AiToolConnectionEntity::getState, AiToolConnectionStatus.ACTIVE.name())
                .set(AiToolConnectionEntity::getState, next.name())
                .set(AiToolConnectionEntity::getRevokedAt, now)
                .set(AiToolConnectionEntity::getUpdatedAt, now));
    }

    private AiToolConnection withSystems(AiToolConnectionEntity entity) {
        List<ConnectedSystem> systems = systemMapper.selectList(Wrappers.<AiToolConnectionSystemEntity>lambdaQuery()
                        .eq(AiToolConnectionSystemEntity::getConnectionId, entity.getId())
                        .orderByAsc(AiToolConnectionSystemEntity::getId))
                .stream().map(this::toSystem).toList();
        return toDomain(entity, systems);
    }

    private ConnectedSystem toSystem(AiToolConnectionSystemEntity row) {
        return new ConnectedSystem(row.getProjectCode(), row.getPublicationId());
    }

    private AiToolConnection toDomain(AiToolConnectionEntity entity, List<ConnectedSystem> systems) {
        return new AiToolConnection(entity.getId(), entity.getTenantId(), entity.getBusinessUserId(),
                entity.getAppId(), entity.getExternalUserId(), entity.getUserName(),
                AiToolKind.valueOf(entity.getToolKind()), entity.getKeyPrefix(),
                AiToolConnectionStatus.valueOf(entity.getState()), entity.getExpiresAt(),
                entity.getLastUsedAt(), entity.getCreatedAt(), systems);
    }
}
