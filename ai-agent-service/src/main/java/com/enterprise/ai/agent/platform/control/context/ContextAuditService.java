package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextAuditService {

    private final ContextAuditEventMapper auditEventMapper;

    public ContextAuditEventEntity record(ContextAuditEventEntity event) {
        if (event.getCreatedAt() == null) {
            event.setCreatedAt(LocalDateTime.now());
        }
        auditEventMapper.insert(event);
        return event;
    }

    public ContextAuditEventEntity record(ContextAuditEventType eventType,
                                          ContextAuditDecision decision,
                                          String reason,
                                          ContextQueryRequest scope,
                                          Long itemId,
                                          Long namespaceId) {
        ContextAuditEventEntity event = new ContextAuditEventEntity();
        event.setEventType(eventType.name());
        event.setDecision(decision == null ? null : decision.name());
        event.setReason(reason);
        event.setItemId(itemId);
        event.setNamespaceId(namespaceId);
        if (scope != null) {
            event.setTenantId(scope.getTenantId());
            event.setProjectId(scope.getProjectId());
            event.setProjectCode(scope.getProjectCode());
            event.setAgentId(scope.getAgentId());
            event.setWorkflowId(scope.getWorkflowId());
            event.setSessionId(scope.getSessionId());
            event.setTraceId(scope.getTraceId());
            event.setActorType(scope.getActorType());
            event.setActorId(scope.getActorId());
        }
        return record(event);
    }

    public ContextAuditEventEntity recordWithMetadata(ContextAuditEventType eventType,
                                                      ContextAuditDecision decision,
                                                      String reason,
                                                      ContextQueryRequest scope,
                                                      Long itemId,
                                                      Long namespaceId,
                                                      String metadataJson) {
        ContextAuditEventEntity event = new ContextAuditEventEntity();
        event.setEventType(eventType.name());
        event.setDecision(decision == null ? null : decision.name());
        event.setReason(reason);
        event.setItemId(itemId);
        event.setNamespaceId(namespaceId);
        event.setMetadataJson(metadataJson);
        if (scope != null) {
            event.setTenantId(scope.getTenantId());
            event.setProjectId(scope.getProjectId());
            event.setProjectCode(scope.getProjectCode());
            event.setAgentId(scope.getAgentId());
            event.setWorkflowId(scope.getWorkflowId());
            event.setSessionId(scope.getSessionId());
            event.setTraceId(scope.getTraceId());
            event.setActorType(scope.getActorType());
            event.setActorId(scope.getActorId());
        }
        return record(event);
    }

    public List<ContextAuditEventResponse> listAuditEvents(String tenantId,
                                                            String projectCode,
                                                            Long itemId,
                                                            int limit) {
        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setTenantId(tenantId);
        request.setProjectCode(projectCode);
        request.setItemId(itemId);
        request.setLimit(limit);
        return listAuditEvents(request);
    }

    public List<ContextAuditEventResponse> listAuditEvents(ContextAuditListRequest request) {
        int safeLimit = 50;
        if (request != null && request.getLimit() != null && request.getLimit() > 0) {
            safeLimit = Math.min(request.getLimit(), 500);
        }
        String normalizedEventType = request != null && StringUtils.hasText(request.getEventType())
                ? request.getEventType().trim().toUpperCase() : null;
        String normalizedDecision = request != null && StringUtils.hasText(request.getDecision())
                ? request.getDecision().trim().toUpperCase() : null;
        var wrapper = Wrappers.lambdaQuery(ContextAuditEventEntity.class)
                .eq(request != null && StringUtils.hasText(request.getTenantId()),
                        ContextAuditEventEntity::getTenantId, request == null ? null : request.getTenantId())
                .eq(request != null && StringUtils.hasText(request.getProjectCode()),
                        ContextAuditEventEntity::getProjectCode, request == null ? null : request.getProjectCode())
                .eq(request != null && request.getProjectId() != null,
                        ContextAuditEventEntity::getProjectId, request == null ? null : request.getProjectId())
                .eq(request != null && request.getItemId() != null,
                        ContextAuditEventEntity::getItemId, request == null ? null : request.getItemId())
                .eq(request != null && request.getNamespaceId() != null,
                        ContextAuditEventEntity::getNamespaceId, request == null ? null : request.getNamespaceId())
                .eq(normalizedEventType != null, ContextAuditEventEntity::getEventType, normalizedEventType)
                .eq(request != null && StringUtils.hasText(request.getActorType()),
                        ContextAuditEventEntity::getActorType, request == null ? null : request.getActorType())
                .eq(request != null && StringUtils.hasText(request.getActorId()),
                        ContextAuditEventEntity::getActorId, request == null ? null : request.getActorId())
                .eq(normalizedDecision != null, ContextAuditEventEntity::getDecision, normalizedDecision)
                .eq(request != null && StringUtils.hasText(request.getTraceId()),
                        ContextAuditEventEntity::getTraceId, request == null ? null : request.getTraceId())
                .ge(request != null && request.getDateFrom() != null,
                        ContextAuditEventEntity::getCreatedAt, request == null ? null : request.getDateFrom())
                .le(request != null && request.getDateTo() != null,
                        ContextAuditEventEntity::getCreatedAt, request == null ? null : request.getDateTo())
                .orderByDesc(ContextAuditEventEntity::getCreatedAt)
                .last("LIMIT " + safeLimit);
        return auditEventMapper.selectList(wrapper).stream()
                .map(ContextViewMapper::toAuditResponse)
                .collect(Collectors.toList());
    }
}
