package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent执行结果，记录完整的推理和工具调用链路
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {

    private boolean success;

    private String answer;

    /** 执行过程中的每一步描述 */
    @Builder.Default
    private List<StepRecord> steps = new ArrayList<>();

    /** 各Tool的调用结果 */
    @Builder.Default
    private Map<String, Object> toolResults = new HashMap<>();

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    public void addStep(String stepName, String detail) {
        steps.add(new StepRecord(stepName, detail));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepRecord {
        private String name;
        private String detail;
    }
}
