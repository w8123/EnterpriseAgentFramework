package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.enterprise.ai.control.mcp.application.port.McpCallAuditRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/** Audit append is best-effort: a failed log write must never fail the protocol call. */
@Repository
@RequiredArgsConstructor
public class MybatisMcpCallAuditRepository implements McpCallAuditRepository {

    private static final Logger LOG = LoggerFactory.getLogger(MybatisMcpCallAuditRepository.class);

    private final McpCallLogMapper callLogMapper;

    @Override
    public void append(Entry entry) {
        try {
            McpCallLogEntity entity = new McpCallLogEntity();
            entity.setDirection(entry.direction());
            entity.setPublicationId(entry.publicationId());
            entity.setClientId(entry.clientId());
            entity.setClientName(entry.clientName());
            entity.setRemoteServerId(entry.remoteServerId());
            entity.setMethod(entry.method());
            entity.setToolName(entry.toolName());
            entity.setProjectId(entry.projectId());
            entity.setProjectCode(entry.projectCode());
            entity.setEnvironment(entry.environment());
            entity.setTenantId(entry.tenantId());
            entity.setSuccess(entry.success());
            entity.setLatencyMs(entry.latencyMs());
            entity.setErrorCategory(entry.errorCategory());
            entity.setRequestBody(entry.requestBody());
            entity.setResponseBody(entry.responseBody());
            entity.setErrorMessage(entry.errorMessage());
            entity.setTraceId(entry.traceId());
            entity.setRunId(entry.runId());
            entity.setRemoteIp(entry.remoteIp());
            entity.setCreatedAt(entry.createdAt());
            callLogMapper.insert(entity);
        } catch (RuntimeException exception) {
            LOG.warn("MCP call audit insert failed for method={} tool={}: {}",
                    entry.method(), entry.toolName(), exception.getMessage());
        }
    }
}
