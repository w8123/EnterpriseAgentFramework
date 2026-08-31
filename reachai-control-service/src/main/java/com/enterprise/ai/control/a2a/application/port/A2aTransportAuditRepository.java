package com.enterprise.ai.control.a2a.application.port;

import java.time.LocalDateTime;

public interface A2aTransportAuditRepository {

    void save(TransportAuditRecord record);

    record TransportAuditRecord(
            String requestId,
            String direction,
            Long principalId,
            Long publicationId,
            Long remoteAgentId,
            Long taskRefId,
            String protocolBinding,
            String protocolVersion,
            String operation,
            boolean success,
            Integer httpStatus,
            String protocolErrorCode,
            Long latencyMs,
            Long requestBytes,
            Long responseBytes,
            String requestPayloadSha256,
            String responsePayloadSha256,
            String requestSummaryJson,
            String responseSummaryJson,
            String remoteAddressSha256,
            String errorSummary,
            String traceId,
            LocalDateTime createdAt) {
    }
}
