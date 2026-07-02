package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimeTraceCenterController {

    private final RuntimeGuardDecisionLogMapper guardDecisionLogMapper;

    @GetMapping("/api/trace-center/guard-decisions")
    public ResponseEntity<List<RuntimeGuardDecisionLogEntity>> listGuardDecisions(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String decisionType,
            @RequestParam(required = false) String targetKind,
            @RequestParam(required = false) String targetName,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit) {
        LambdaQueryWrapper<RuntimeGuardDecisionLogEntity> wrapper =
                new LambdaQueryWrapper<RuntimeGuardDecisionLogEntity>()
                        .eq(StringUtils.hasText(traceId), RuntimeGuardDecisionLogEntity::getTraceId, traceId)
                        .eq(StringUtils.hasText(decisionType), RuntimeGuardDecisionLogEntity::getDecisionType, decisionType)
                        .eq(StringUtils.hasText(targetKind), RuntimeGuardDecisionLogEntity::getTargetKind, targetKind)
                        .eq(StringUtils.hasText(targetName), RuntimeGuardDecisionLogEntity::getTargetName, targetName)
                        .eq(StringUtils.hasText(decision), RuntimeGuardDecisionLogEntity::getDecision, decision)
                        .ge(StringUtils.hasText(from), RuntimeGuardDecisionLogEntity::getCreatedAt, from)
                        .le(StringUtils.hasText(to), RuntimeGuardDecisionLogEntity::getCreatedAt, to)
                        .orderByDesc(RuntimeGuardDecisionLogEntity::getCreatedAt)
                        .orderByDesc(RuntimeGuardDecisionLogEntity::getId)
                        .last("limit " + safeLimit(limit, 200));
        return ResponseEntity.ok(guardDecisionLogMapper.selectList(wrapper));
    }

    private int safeLimit(int requested, int fallback) {
        int value = requested <= 0 ? fallback : requested;
        return Math.min(Math.max(value, 1), 1000);
    }
}
