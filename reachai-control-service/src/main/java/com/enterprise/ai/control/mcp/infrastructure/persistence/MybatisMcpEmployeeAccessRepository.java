package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MybatisMcpEmployeeAccessRepository implements McpEmployeeAccessRepository {

    private final McpEmployeeAccessMapper mapper;

    @Override
    public List<McpEmployeeAccess> findEnabled() {
        return mapper.selectList(Wrappers.<McpEmployeeAccessEntity>lambdaQuery()
                        .eq(McpEmployeeAccessEntity::getEnabled, true)
                        .orderByAsc(McpEmployeeAccessEntity::getId))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<McpEmployeeAccess> findByProjectCode(String projectCode) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<McpEmployeeAccessEntity>lambdaQuery()
                        .eq(McpEmployeeAccessEntity::getProjectCode, projectCode)
                        .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public Optional<McpEmployeeAccess> findByPublicationId(long publicationId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<McpEmployeeAccessEntity>lambdaQuery()
                        .eq(McpEmployeeAccessEntity::getPublicationId, publicationId)
                        .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public McpEmployeeAccess save(McpEmployeeAccess access) {
        LocalDateTime now = LocalDateTime.now();
        McpEmployeeAccessEntity entity = new McpEmployeeAccessEntity();
        entity.setId(access.id());
        entity.setPublicationId(access.publicationId());
        entity.setProjectId(access.projectId());
        entity.setProjectCode(access.projectCode());
        entity.setDisplayName(access.displayName());
        entity.setEnabled(access.enabled());
        entity.setWriteApprovalRequired(access.writeApprovalRequired());
        entity.setUpdatedAt(now);
        try {
            if (entity.getId() == null) {
                entity.setCreatedAt(now);
                mapper.insert(entity);
            } else {
                mapper.updateById(entity);
            }
        } catch (DuplicateKeyException duplicate) {
            throw new McpDomainException("MCP_EMPLOYEE_ACCESS_CONFLICT",
                    "each business system can have only one employee publication, and each publication one system");
        }
        return toDomain(entity);
    }

    private McpEmployeeAccess toDomain(McpEmployeeAccessEntity entity) {
        return new McpEmployeeAccess(entity.getId(), entity.getPublicationId(), entity.getProjectId(),
                entity.getProjectCode(),
                entity.getDisplayName(), Boolean.TRUE.equals(entity.getEnabled()),
                !Boolean.FALSE.equals(entity.getWriteApprovalRequired()));
    }
}
