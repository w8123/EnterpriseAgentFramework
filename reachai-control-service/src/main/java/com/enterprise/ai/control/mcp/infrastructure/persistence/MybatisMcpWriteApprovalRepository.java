package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.port.McpWriteApprovalRepository;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApproval;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApprovalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MybatisMcpWriteApprovalRepository implements McpWriteApprovalRepository {

    private static final String PENDING = McpWriteApprovalStatus.PENDING.name();

    private final McpWriteApprovalMapper mapper;

    @Override
    public McpWriteApproval create(McpWriteApproval approval) {
        McpWriteApprovalEntity entity = toEntity(approval);
        LocalDateTime now = approval.createdAt() == null ? LocalDateTime.now() : approval.createdAt();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<McpWriteApproval> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<McpWriteApproval> findOpenDuplicate(long connectionId, String toolName, String argumentsHash,
                                                        LocalDateTime now) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<McpWriteApprovalEntity>lambdaQuery()
                        .eq(McpWriteApprovalEntity::getConnectionId, connectionId)
                        .eq(McpWriteApprovalEntity::getToolName, toolName)
                        .eq(McpWriteApprovalEntity::getArgumentsHash, argumentsHash)
                        .eq(McpWriteApprovalEntity::getState, PENDING)
                        .gt(McpWriteApprovalEntity::getExpiresAt, now)
                        .orderByDesc(McpWriteApprovalEntity::getId)
                        .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public long countOpenByOwner(String tenantId, long businessUserId, LocalDateTime now) {
        return mapper.selectCount(Wrappers.<McpWriteApprovalEntity>lambdaQuery()
                .eq(McpWriteApprovalEntity::getTenantId, tenantId)
                .eq(McpWriteApprovalEntity::getBusinessUserId, businessUserId)
                .eq(McpWriteApprovalEntity::getState, PENDING)
                .gt(McpWriteApprovalEntity::getExpiresAt, now));
    }

    @Override
    public List<McpWriteApproval> findByOwner(String tenantId, long businessUserId, int limit) {
        return mapper.selectList(Wrappers.<McpWriteApprovalEntity>lambdaQuery()
                        .eq(McpWriteApprovalEntity::getTenantId, tenantId)
                        .eq(McpWriteApprovalEntity::getBusinessUserId, businessUserId)
                        .orderByDesc(McpWriteApprovalEntity::getId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public boolean decide(long id, McpWriteApprovalStatus next, String decidedBy, String comment,
                          LocalDateTime now) {
        return mapper.update(null, Wrappers.<McpWriteApprovalEntity>lambdaUpdate()
                .eq(McpWriteApprovalEntity::getId, id)
                .eq(McpWriteApprovalEntity::getState, PENDING)
                .gt(McpWriteApprovalEntity::getExpiresAt, now)
                .set(McpWriteApprovalEntity::getState, next.name())
                .set(McpWriteApprovalEntity::getDecidedBy, decidedBy)
                .set(McpWriteApprovalEntity::getDecidedAt, now)
                .set(McpWriteApprovalEntity::getDecisionComment, comment)
                .set(McpWriteApprovalEntity::getUpdatedAt, now)) == 1;
    }

    @Override
    public void finish(long id, McpWriteApprovalStatus outcome, String resultJson, String errorCode, String runId,
                       String traceId, LocalDateTime now) {
        mapper.update(null, Wrappers.<McpWriteApprovalEntity>lambdaUpdate()
                .eq(McpWriteApprovalEntity::getId, id)
                .eq(McpWriteApprovalEntity::getState, McpWriteApprovalStatus.EXECUTING.name())
                .set(McpWriteApprovalEntity::getState, outcome.name())
                .set(McpWriteApprovalEntity::getResultJson, resultJson)
                .set(McpWriteApprovalEntity::getErrorCode, errorCode)
                .set(McpWriteApprovalEntity::getRunId, runId)
                .set(McpWriteApprovalEntity::getTraceId, traceId)
                .set(McpWriteApprovalEntity::getExecutedAt, now)
                .set(McpWriteApprovalEntity::getUpdatedAt, now));
    }

    @Override
    public int expireLapsed(LocalDateTime now) {
        return mapper.update(null, Wrappers.<McpWriteApprovalEntity>lambdaUpdate()
                .eq(McpWriteApprovalEntity::getState, PENDING)
                .le(McpWriteApprovalEntity::getExpiresAt, now)
                .set(McpWriteApprovalEntity::getState, McpWriteApprovalStatus.EXPIRED.name())
                .set(McpWriteApprovalEntity::getUpdatedAt, now));
    }

    @Override
    public SearchPage search(SearchQuery query, LocalDateTime now) {
        var wrapper = Wrappers.<McpWriteApprovalEntity>lambdaQuery();
        if (StringUtils.hasText(query.projectCode())) {
            wrapper.eq(McpWriteApprovalEntity::getProjectCode, query.projectCode().trim());
        }
        if (StringUtils.hasText(query.keyword())) {
            String keyword = query.keyword().trim();
            wrapper.and(q -> q.like(McpWriteApprovalEntity::getUserName, keyword)
                    .or().eq(McpWriteApprovalEntity::getExternalUserId, keyword)
                    .or().like(McpWriteApprovalEntity::getToolName, keyword));
        }
        String state = StringUtils.hasText(query.state()) ? query.state().trim().toUpperCase(Locale.ROOT) : null;
        if (PENDING.equals(state)) {
            wrapper.eq(McpWriteApprovalEntity::getState, PENDING).gt(McpWriteApprovalEntity::getExpiresAt, now);
        } else if (McpWriteApprovalStatus.EXPIRED.name().equals(state)) {
            wrapper.and(q -> q.eq(McpWriteApprovalEntity::getState, state)
                    .or(open -> open.eq(McpWriteApprovalEntity::getState, PENDING)
                            .le(McpWriteApprovalEntity::getExpiresAt, now)));
        } else if (state != null) {
            wrapper.eq(McpWriteApprovalEntity::getState, state);
        }
        wrapper.orderByDesc(McpWriteApprovalEntity::getId);
        long total = mapper.selectCount(wrapper);
        wrapper.last("LIMIT " + query.limit() + " OFFSET " + query.offset());
        return new SearchPage(mapper.selectList(wrapper).stream().map(this::toDomain).toList(), total);
    }

    @Override
    public long countPending(LocalDateTime now) {
        return mapper.selectCount(Wrappers.<McpWriteApprovalEntity>lambdaQuery()
                .eq(McpWriteApprovalEntity::getState, PENDING)
                .gt(McpWriteApprovalEntity::getExpiresAt, now));
    }

    private McpWriteApprovalEntity toEntity(McpWriteApproval approval) {
        McpWriteApprovalEntity entity = new McpWriteApprovalEntity();
        entity.setId(approval.id());
        entity.setTenantId(approval.tenantId());
        entity.setConnectionId(approval.connectionId());
        entity.setBusinessUserId(approval.businessUserId());
        entity.setExternalUserId(approval.externalUserId());
        entity.setUserName(approval.userName());
        entity.setToolKind(approval.toolKind());
        entity.setProjectId(approval.projectId());
        entity.setProjectCode(approval.projectCode());
        entity.setSystemName(approval.systemName());
        entity.setPublicationId(approval.publicationId());
        entity.setRevisionNo(approval.revisionNo());
        entity.setToolName(approval.toolName());
        entity.setToolDescription(approval.toolDescription());
        entity.setSourceKind(approval.sourceKind());
        entity.setSourceRef(approval.sourceRef());
        entity.setWorkflowVersionId(approval.workflowVersionId());
        entity.setCapabilityContractHash(approval.capabilityContractHash());
        entity.setRiskLevel(approval.riskLevel());
        entity.setArgumentsJson(approval.argumentsJson());
        entity.setArgumentsHash(approval.argumentsHash());
        entity.setState(approval.state().name());
        entity.setDecidedBy(approval.decidedBy());
        entity.setDecidedAt(approval.decidedAt());
        entity.setDecisionComment(approval.decisionComment());
        entity.setExecutedAt(approval.executedAt());
        entity.setResultJson(approval.resultJson());
        entity.setErrorCode(approval.errorCode());
        entity.setRunId(approval.runId());
        entity.setTraceId(approval.traceId());
        entity.setExpiresAt(approval.expiresAt());
        return entity;
    }

    private McpWriteApproval toDomain(McpWriteApprovalEntity entity) {
        return new McpWriteApproval(entity.getId(), entity.getTenantId(),
                entity.getConnectionId() == null ? 0L : entity.getConnectionId(),
                entity.getBusinessUserId(), entity.getExternalUserId(), entity.getUserName(), entity.getToolKind(),
                entity.getProjectId(), entity.getProjectCode(), entity.getSystemName(),
                entity.getPublicationId() == null ? 0L : entity.getPublicationId(),
                entity.getRevisionNo() == null ? 0 : entity.getRevisionNo(),
                entity.getToolName(), entity.getToolDescription(), entity.getSourceKind(), entity.getSourceRef(),
                entity.getWorkflowVersionId(), entity.getCapabilityContractHash(), entity.getRiskLevel(),
                entity.getArgumentsJson(), entity.getArgumentsHash(),
                McpWriteApprovalStatus.valueOf(entity.getState()),
                entity.getDecidedBy(), entity.getDecidedAt(), entity.getDecisionComment(), entity.getExecutedAt(),
                entity.getResultJson(), entity.getErrorCode(), entity.getRunId(), entity.getTraceId(),
                entity.getExpiresAt(), entity.getCreatedAt());
    }
}
