package com.enterprise.ai.capability.client.runtime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.List;

@FeignClient(name = "reachai-runtime-trace", url = "${services.runtime-service.url:http://localhost:18604}")
public interface CapabilityRuntimeTraceClient {

    @GetMapping("/internal/runtime/tool-call-logs/by-tool")
    List<ToolCallLogRecord> listToolCallsByTool(@RequestParam("toolName") String toolName,
                                                @RequestParam("days") int days);

    @GetMapping("/internal/runtime/tool-call-logs/recent")
    List<ToolCallLogRecord> listRecentToolCalls(@RequestParam("days") int days);

    @GetMapping("/internal/runtime/tool-call-logs/by-trace/{traceId}")
    List<ToolCallLogRecord> listToolCallsByTrace(@PathVariable("traceId") String traceId);

    @DeleteMapping("/internal/runtime/tool-call-logs/demo")
    DeleteResult deleteDemoToolCalls();

    @PostMapping("/internal/runtime/tool-call-logs/demo")
    AppendResult appendDemoToolCalls(@RequestBody List<ToolCallLogCreateRequest> requests);

    record ToolCallLogRecord(
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

    record ToolCallLogCreateRequest(
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

    record DeleteResult(int deletedCount) {
    }

    record AppendResult(int insertedCount) {
    }
}
