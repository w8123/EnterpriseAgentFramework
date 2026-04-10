package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent接口 — 复杂智能体任务入口
 * <p>
 * 完整执行意图识别 → Agent编排 → 工具调用 → 多步推理流程。
 * /execute 返回精简结果，/execute/detailed 返回含完整步骤链的详细结果。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * 执行Agent任务，返回精简响应
     */
    @PostMapping("/execute")
    public ResponseEntity<ChatResponse> executeAgent(@Valid @RequestBody ChatRequest request) {
        log.info("收到Agent执行请求: userId={}", request.getUserId());
        ChatResponse response = agentService.executeAgent(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 执行Agent任务，返回完整推理过程（适用于调试和可观测性场景）
     */
    @PostMapping("/execute/detailed")
    public ResponseEntity<AgentResult> executeAgentDetailed(@Valid @RequestBody ChatRequest request) {
        log.info("收到Agent详细执行请求: userId={}", request.getUserId());
        AgentResult result = agentService.executeAgentDetailed(request);
        return ResponseEntity.ok(result);
    }
}
