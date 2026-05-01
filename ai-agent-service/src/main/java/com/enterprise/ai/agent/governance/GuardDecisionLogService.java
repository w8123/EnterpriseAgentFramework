package com.enterprise.ai.agent.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GuardDecisionLogService {

    private final GuardDecisionLogMapper mapper;
    private final ObjectMapper objectMapper;

    public GuardDecisionLogService(GuardDecisionLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(String traceId,
                       String decisionType,
                       String targetKind,
                       String targetName,
                       String decision,
                       String reason,
                       Map<String, ?> metadata) {
        try {
            GuardDecisionLogEntity entity = new GuardDecisionLogEntity();
            entity.setTraceId(traceId);
            entity.setDecisionType(decisionType);
            entity.setTargetKind(targetKind);
            entity.setTargetName(targetName);
            entity.setDecision(decision);
            entity.setReason(reason);
            entity.setMetadataJson(toJson(metadata));
            entity.setCreatedAt(LocalDateTime.now());
            mapper.insert(entity);
        } catch (Exception ex) {
            log.warn("[GuardDecisionLog] 写入失败: traceId={}, target={}/{}, err={}",
                    traceId, targetKind, targetName, ex.toString());
        }
    }

    public List<GuardDecisionLogEntity> search(SearchQuery query) {
        int limit = query.limit() == null ? 100 : Math.max(1, Math.min(query.limit(), 500));
        LambdaQueryWrapper<GuardDecisionLogEntity> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.traceId())) {
            qw.eq(GuardDecisionLogEntity::getTraceId, query.traceId());
        }
        if (StringUtils.hasText(query.decisionType())) {
            qw.eq(GuardDecisionLogEntity::getDecisionType, query.decisionType());
        }
        if (StringUtils.hasText(query.targetKind())) {
            qw.eq(GuardDecisionLogEntity::getTargetKind, query.targetKind());
        }
        if (StringUtils.hasText(query.targetName())) {
            qw.eq(GuardDecisionLogEntity::getTargetName, query.targetName());
        }
        if (StringUtils.hasText(query.decision())) {
            qw.eq(GuardDecisionLogEntity::getDecision, query.decision());
        }
        if (query.from() != null) {
            qw.ge(GuardDecisionLogEntity::getCreatedAt, query.from());
        }
        if (query.to() != null) {
            qw.le(GuardDecisionLogEntity::getCreatedAt, query.to());
        }
        qw.orderByDesc(GuardDecisionLogEntity::getId).last("limit " + limit);
        return mapper.selectList(qw);
    }

    private String toJson(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            return String.valueOf(metadata);
        }
    }

    public record SearchQuery(String traceId,
                              String decisionType,
                              String targetKind,
                              String targetName,
                              String decision,
                              LocalDateTime from,
                              LocalDateTime to,
                              Integer limit) {
    }
}
