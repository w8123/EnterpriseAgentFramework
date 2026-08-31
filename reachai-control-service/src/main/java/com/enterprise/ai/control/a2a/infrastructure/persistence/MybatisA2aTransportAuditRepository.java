package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.enterprise.ai.control.a2a.application.port.A2aTransportAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisA2aTransportAuditRepository implements A2aTransportAuditRepository {

    private final A2aTransportEventMapper mapper;

    @Override
    public void save(TransportAuditRecord value) {
        A2aTransportEventEntity entity = new A2aTransportEventEntity();
        entity.setRequestId(value.requestId());
        entity.setDirection(value.direction());
        entity.setPrincipalId(value.principalId());
        entity.setPublicationId(value.publicationId());
        entity.setRemoteAgentId(value.remoteAgentId());
        entity.setTaskRefId(value.taskRefId());
        entity.setProtocolBinding(value.protocolBinding());
        entity.setProtocolVersion(value.protocolVersion());
        entity.setOperation(value.operation());
        entity.setSuccess(value.success());
        entity.setHttpStatus(value.httpStatus());
        entity.setProtocolErrorCode(value.protocolErrorCode());
        entity.setLatencyMs(value.latencyMs());
        entity.setRequestBytes(value.requestBytes());
        entity.setResponseBytes(value.responseBytes());
        entity.setRequestPayloadSha256(value.requestPayloadSha256());
        entity.setResponsePayloadSha256(value.responsePayloadSha256());
        entity.setRequestSummaryJson(value.requestSummaryJson());
        entity.setResponseSummaryJson(value.responseSummaryJson());
        entity.setRemoteAddressSha256(value.remoteAddressSha256());
        entity.setErrorSummary(value.errorSummary());
        entity.setTraceId(value.traceId());
        entity.setCreatedAt(value.createdAt());
        mapper.insert(entity);
    }
}
