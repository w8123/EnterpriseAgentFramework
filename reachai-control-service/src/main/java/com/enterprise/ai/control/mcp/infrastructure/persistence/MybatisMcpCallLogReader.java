package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.calllog.McpCallLogReader;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisMcpCallLogReader implements McpCallLogReader {

    static final String TOOL_CALL_METHOD = "tools/call";
    /** 查审批进度只是轮询，不是员工让 AI 办的事。 */
    static final String APPROVAL_STATUS_TOOL = "reachai_approval_status";

    private final McpCallLogMapper callLogMapper;

    public MybatisMcpCallLogReader(McpCallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    @Override
    public Page page(String direction, Long publicationId, Long clientId, Long connectionId, String method,
                     String toolName, Boolean success, String errorCategory, Integer days,
                     int limit, int offset) {
        var query = Wrappers.<McpCallLogEntity>lambdaQuery()
                .eq(StringUtils.hasText(direction), McpCallLogEntity::getDirection,
                        StringUtils.hasText(direction) ? direction.trim().toUpperCase() : null)
                .eq(publicationId != null, McpCallLogEntity::getPublicationId, publicationId)
                .eq(clientId != null, McpCallLogEntity::getClientId, clientId)
                .eq(connectionId != null, McpCallLogEntity::getConnectionId, connectionId)
                .eq(StringUtils.hasText(method), McpCallLogEntity::getMethod, trim(method))
                .eq(StringUtils.hasText(toolName), McpCallLogEntity::getToolName, trim(toolName))
                .eq(success != null, McpCallLogEntity::getSuccess, success)
                .eq(StringUtils.hasText(errorCategory), McpCallLogEntity::getErrorCategory,
                        trim(errorCategory))
                .ge(days != null && days > 0, McpCallLogEntity::getCreatedAt,
                        days == null ? null : LocalDateTime.now().minusDays(days))
                .orderByDesc(McpCallLogEntity::getId);
        long total = callLogMapper.selectCount(query);
        query.last("LIMIT " + limit + " OFFSET " + offset);
        return new Page(callLogMapper.selectList(query).stream().map(this::toEntry).toList(),
                total, limit, offset);
    }

    @Override
    public Entry detail(long id) {
        return Optional.ofNullable(callLogMapper.selectById(id))
                .map(this::toEntry)
                .orElse(null);
    }

    @Override
    public List<Entry> recentByConnections(List<Long> connectionIds, int limit) {
        if (connectionIds == null || connectionIds.isEmpty()) {
            return List.of();
        }
        var query = Wrappers.<McpCallLogEntity>lambdaQuery()
                .in(McpCallLogEntity::getConnectionId, connectionIds)
                // 员工看的是「AI 替我办了哪些事」：握手、工具列表、传输层错误这些协议行不算。
                .eq(McpCallLogEntity::getMethod, TOOL_CALL_METHOD)
                .isNotNull(McpCallLogEntity::getToolName)
                .ne(McpCallLogEntity::getToolName, APPROVAL_STATUS_TOOL)
                .orderByDesc(McpCallLogEntity::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return callLogMapper.selectList(query).stream().map(this::toEntry).map(Entry::withoutBodies).toList();
    }

    private Entry toEntry(McpCallLogEntity entity) {
        return new Entry(
                entity.getId(),
                entity.getDirection(),
                entity.getPublicationId(),
                entity.getClientId(),
                entity.getClientName(),
                entity.getMethod(),
                entity.getToolName(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getEnvironment(),
                entity.getTenantId(),
                entity.getSuccess(),
                entity.getLatencyMs(),
                entity.getErrorCategory(),
                entity.getRequestBody(),
                entity.getResponseBody(),
                entity.getErrorMessage(),
                entity.getTraceId(),
                entity.getRunId(),
                entity.getRemoteIp(),
                entity.getCreatedAt(),
                entity.getConnectionId(),
                entity.getExternalUserId(),
                entity.getToolClientName());
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
