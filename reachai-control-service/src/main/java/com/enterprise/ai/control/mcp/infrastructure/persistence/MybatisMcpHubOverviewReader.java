package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.overview.McpHubOverviewReader;
import com.enterprise.ai.control.mcp.domain.client.McpClientStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class MybatisMcpHubOverviewReader implements McpHubOverviewReader {

    private final McpPublicationMapper publicationMapper;
    private final McpClientMapper clientMapper;
    private final McpCallLogMapper callLogMapper;

    @Override
    public McpHubOverviewView read(int days) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(Math.max(days, 1));
        long publications = publicationMapper.selectCount(Wrappers.emptyWrapper());
        long publishedPublications = publicationMapper.selectCount(
                Wrappers.<McpPublicationEntity>lambdaQuery()
                        .eq(McpPublicationEntity::getState, "PUBLISHED"));
        long activeClients = clientMapper.selectCount(
                Wrappers.<McpClientEntity>lambdaQuery()
                        .eq(McpClientEntity::getState, McpClientStatus.ACTIVE.name())
                        .eq(McpClientEntity::getEnabled, true)
                        .and(scope -> scope.isNull(McpClientEntity::getExpiresAt)
                                .or().gt(McpClientEntity::getExpiresAt, now)));
        long expiringCredentials = clientMapper.selectCount(
                Wrappers.<McpClientEntity>lambdaQuery()
                        .eq(McpClientEntity::getState, McpClientStatus.ACTIVE.name())
                        .eq(McpClientEntity::getEnabled, true)
                        .isNotNull(McpClientEntity::getExpiresAt)
                        .ge(McpClientEntity::getExpiresAt, now)
                        .le(McpClientEntity::getExpiresAt, now.plusDays(7)));
        long outboundCalls = callLogMapper.selectCount(
                Wrappers.<McpCallLogEntity>lambdaQuery()
                        .eq(McpCallLogEntity::getDirection, "OUTBOUND")
                        .ge(McpCallLogEntity::getCreatedAt, since));
        long outboundSuccesses = callLogMapper.selectCount(
                Wrappers.<McpCallLogEntity>lambdaQuery()
                        .eq(McpCallLogEntity::getDirection, "OUTBOUND")
                        .eq(McpCallLogEntity::getSuccess, true)
                        .ge(McpCallLogEntity::getCreatedAt, since));
        long inboundCalls = callLogMapper.selectCount(
                Wrappers.<McpCallLogEntity>lambdaQuery()
                        .eq(McpCallLogEntity::getDirection, "INBOUND")
                        .ge(McpCallLogEntity::getCreatedAt, since));
        Long p95 = outboundP95Ms(outboundCalls, since);
        return new McpHubOverviewView(
                publications,
                publishedPublications,
                activeClients,
                expiringCredentials,
                outboundCalls,
                outboundCalls == 0 ? 1.0 : (double) outboundSuccesses / outboundCalls,
                p95,
                inboundCalls);
    }

    private Long outboundP95Ms(long outboundCalls, LocalDateTime since) {
        if (outboundCalls == 0) {
            return null;
        }
        long offset = Math.max((long) Math.floor(outboundCalls * 0.05), 0);
        McpCallLogEntity row = callLogMapper.selectOne(Wrappers.<McpCallLogEntity>lambdaQuery()
                .select(McpCallLogEntity::getLatencyMs)
                .eq(McpCallLogEntity::getDirection, "OUTBOUND")
                .isNotNull(McpCallLogEntity::getLatencyMs)
                .ge(McpCallLogEntity::getCreatedAt, since)
                .orderByDesc(McpCallLogEntity::getLatencyMs)
                .last("LIMIT 1 OFFSET " + offset));
        return row == null ? null : row.getLatencyMs();
    }
}
