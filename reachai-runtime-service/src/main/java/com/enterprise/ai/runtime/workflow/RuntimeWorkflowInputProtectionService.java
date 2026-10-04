package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.kernel.RuntimeHttpApiInputBindings;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Safe Agent approval/model presentation for owner-pinned HTTP API inputs; never alters execution arguments. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowInputProtectionService {
    private final RuntimeWorkflowExecutionQuery workflows;
    private final RuntimeWorkflowHttpApiService apis;
    private final ObjectMapper json;

    public Map<String, Object> protect(String workflowId, Long versionId, Map<String, Object> arguments) {
        try {
            var target = workflows.resolve(List.of(new RuntimeWorkflowExecutionQuery.Reference(workflowId, versionId))).get(0);
            return protectGraph(target.version().getGraphSpecSnapshotJson(), arguments);
        } catch (RuntimeException unavailable) {
            return WorkflowTraceSanitizer.sanitizeArgs(arguments);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> protectGraph(String graphJson, Map<String, Object> arguments) {
        Map<String, Object> original = arguments == null ? Map.of() : arguments;
        try {
            GraphSpec graph = json.readValue(graphJson, GraphSpec.class);
            Map<String, Object> context = new LinkedHashMap<>(original);
            context.put("params", original); context.put("input", original);
            Map<String, Object> safe = new LinkedHashMap<>(original);
            for (var node : graph.getNodes()) {
                if (node.getRef() == null || node.getRef().getQualifiedName() == null
                        || !node.getRef().getQualifiedName().startsWith("http-api:")) continue;
                var protection = apis.prepareInputProtection(node, RuntimeHttpApiInputBindings.resolve(node, context));
                safe = (Map<String, Object>) protection.protect(safe);
            }
            return safe;
        } catch (Exception unavailable) {
            // Stale/unavailable owner facts must never expose unclassified strings on the confirmation card/model payload.
            return WorkflowTraceSanitizer.sanitizeArgs(original);
        }
    }
}
