package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Clears opt-in raw MCP payloads while retaining the non-sensitive audit evidence row. */
@Service
@RequiredArgsConstructor
public class McpAuditPayloadRetentionService {

    private final McpCallLogMapper mapper;
    private final McpHubProperties properties;

    @Scheduled(cron = "${reachai.mcp-hub.audit-payload-cleanup-cron:0 25 3 * * *}")
    public int clearExpiredPayloads() {
        if (!properties.isEnabled() || !properties.isCaptureAuditPayload()) {
            return 0;
        }
        int days = Math.max(1, Math.min(90, properties.getAuditPayloadRetentionDays()));
        return mapper.update(null, Wrappers.<McpCallLogEntity>lambdaUpdate()
                .lt(McpCallLogEntity::getCreatedAt, LocalDateTime.now().minusDays(days))
                .and(wrapper -> wrapper.isNotNull(McpCallLogEntity::getRequestBody)
                        .or().isNotNull(McpCallLogEntity::getResponseBody))
                .set(McpCallLogEntity::getRequestBody, null)
                .set(McpCallLogEntity::getResponseBody, null));
    }
}
