package com.enterprise.ai.agent.mining;

import com.enterprise.ai.agent.tool.log.ToolCallLogEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolChainAggregator {

    public List<ToolChain> aggregate(List<ToolCallLogEntity> logs) {
        Map<String, List<ToolCallLogEntity>> grouped = new LinkedHashMap<>();
        for (ToolCallLogEntity log : logs) {
            if (log.getTraceId() == null || log.getToolName() == null) {
                continue;
            }
            grouped.computeIfAbsent(log.getTraceId(), k -> new ArrayList<>()).add(log);
        }
        List<ToolChain> chains = new ArrayList<>();
        for (Map.Entry<String, List<ToolCallLogEntity>> entry : grouped.entrySet()) {
            List<String> sequence = entry.getValue().stream().map(ToolCallLogEntity::getToolName).toList();
            if (sequence.size() >= 2) {
                chains.add(new ToolChain(entry.getKey(), sequence));
            }
        }
        return chains;
    }

    public record ToolChain(String traceId, List<String> sequence) {}
}
