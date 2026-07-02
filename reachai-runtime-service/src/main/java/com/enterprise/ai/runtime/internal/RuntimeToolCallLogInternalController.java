package com.enterprise.ai.runtime.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/internal/runtime/tool-call-logs")
@RequiredArgsConstructor
public class RuntimeToolCallLogInternalController {

    private static final String DEMO_USER_ID = "demo:skill-mining";

    private final RuntimeToolCallLogMapper mapper;

    @GetMapping("/by-tool")
    public ResponseEntity<List<ToolCallLogRecord>> listByTool(@RequestParam String toolName,
                                                              @RequestParam(defaultValue = "7") int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        List<RuntimeToolCallLogEntity> rows = mapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .eq(RuntimeToolCallLogEntity::getToolName, toolName)
                .ge(RuntimeToolCallLogEntity::getCreateTime, LocalDateTime.now().minusDays(safeDays))
                .orderByAsc(RuntimeToolCallLogEntity::getCreateTime));
        return ResponseEntity.ok(rows.stream().map(this::toRecord).toList());
    }

    @GetMapping("/recent")
    public ResponseEntity<List<ToolCallLogRecord>> listRecent(@RequestParam(defaultValue = "7") int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        List<RuntimeToolCallLogEntity> rows = mapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .ge(RuntimeToolCallLogEntity::getCreateTime, LocalDateTime.now().minusDays(safeDays)));
        return ResponseEntity.ok(rows.stream().map(this::toRecord).toList());
    }

    @GetMapping("/by-trace/{traceId}")
    public ResponseEntity<List<ToolCallLogRecord>> listByTrace(@PathVariable String traceId) {
        List<RuntimeToolCallLogEntity> rows = mapper.selectList(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .eq(RuntimeToolCallLogEntity::getTraceId, traceId)
                .orderByAsc(RuntimeToolCallLogEntity::getCreateTime));
        return ResponseEntity.ok(rows.stream().map(this::toRecord).toList());
    }

    @DeleteMapping("/demo")
    public ResponseEntity<DeleteResult> deleteDemoLogs() {
        int deleted = mapper.delete(new LambdaQueryWrapper<RuntimeToolCallLogEntity>()
                .eq(RuntimeToolCallLogEntity::getUserId, DEMO_USER_ID));
        return ResponseEntity.ok(new DeleteResult(deleted));
    }

    @PostMapping("/demo")
    public ResponseEntity<AppendResult> appendDemoLogs(@RequestBody List<ToolCallLogCreateRequest> requests) {
        int inserted = 0;
        for (ToolCallLogCreateRequest request : requests == null ? List.<ToolCallLogCreateRequest>of() : requests) {
            if (!StringUtils.hasText(request.traceId()) || !StringUtils.hasText(request.toolName())) {
                continue;
            }
            RuntimeToolCallLogEntity entity = new RuntimeToolCallLogEntity();
            entity.setTraceId(request.traceId());
            entity.setSessionId(request.sessionId());
            entity.setUserId(DEMO_USER_ID);
            entity.setAgentName(request.agentName());
            entity.setIntentType(request.intentType());
            entity.setToolName(request.toolName());
            entity.setArgsJson(request.argsJson());
            entity.setResultSummary(request.resultSummary());
            entity.setSuccess(request.success());
            entity.setErrorCode(request.errorCode());
            entity.setElapsedMs(request.elapsedMs());
            entity.setTokenCost(request.tokenCost());
            entity.setCreateTime(request.createTime() == null ? LocalDateTime.now() : request.createTime());
            mapper.insert(entity);
            inserted++;
        }
        return ResponseEntity.ok(new AppendResult(inserted));
    }

    private ToolCallLogRecord toRecord(RuntimeToolCallLogEntity entity) {
        return new ToolCallLogRecord(
                entity.getId(),
                entity.getTraceId(),
                entity.getSessionId(),
                entity.getUserId(),
                entity.getAgentName(),
                entity.getIntentType(),
                entity.getToolName(),
                entity.getArgsJson(),
                entity.getResultSummary(),
                entity.getSuccess(),
                entity.getErrorCode(),
                entity.getElapsedMs(),
                entity.getTokenCost(),
                entity.getCreateTime()
        );
    }

    public record ToolCallLogRecord(
            Long id,
            String traceId,
            String sessionId,
            String userId,
            String agentName,
            String intentType,
            String toolName,
            String argsJson,
            String resultSummary,
            Boolean success,
            String errorCode,
            Integer elapsedMs,
            Integer tokenCost,
            LocalDateTime createTime
    ) {
    }

    public record ToolCallLogCreateRequest(
            String traceId,
            String sessionId,
            String agentName,
            String intentType,
            String toolName,
            String argsJson,
            String resultSummary,
            Boolean success,
            String errorCode,
            Integer elapsedMs,
            Integer tokenCost,
            LocalDateTime createTime
    ) {
    }

    public record DeleteResult(int deletedCount) {
    }

    public record AppendResult(int insertedCount) {
    }
}
