package com.enterprise.ai.agent.eval;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class AgentEvalSuggestionService {

    public Suggestion suggest(Map<String, Long> failedNodeCounts, int failedCount) {
        if (failedCount <= 0) {
            return new Suggestion("All eval executions passed. No repair suggestion is needed.", List.of());
        }
        List<Item> items = failedNodeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new Item(
                        entry.getKey(),
                        entry.getValue() >= 3 ? "HIGH" : "MEDIUM",
                        entry.getValue() + " eval executions failed around this node or final answer.",
                        recommendation(entry.getKey())))
                .toList();
        String focus = items.stream()
                .map(Item::nodeId)
                .limit(3)
                .reduce((left, right) -> left + ", " + right)
                .orElse("final_answer");
        return new Suggestion(failedCount + " eval executions did not pass, mainly around " + focus + ".", items);
    }

    private String recommendation(String nodeId) {
        if ("final_answer".equals(nodeId)) {
            return "Tighten the final answer prompt and ensure it cites the expected fields from workflow state.";
        }
        return "Review node " + nodeId + " input mapping, tool result handling, and output schema against failed cases.";
    }

    public record Suggestion(String summary, List<Item> items) {
    }

    public record Item(String nodeId, String severity, String reason, String recommendation) {
    }
}
