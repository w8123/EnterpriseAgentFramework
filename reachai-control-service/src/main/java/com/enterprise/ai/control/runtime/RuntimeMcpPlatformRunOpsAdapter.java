package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformRunOpsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
final class RuntimeMcpPlatformRunOpsAdapter implements McpPlatformRunOpsPort {
    private final ControlRuntimePublicController runtime;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public ResponseEntity<?> call(PlatformAuthenticatedSession actor, Operation operation, String id, String secondId,
                                  Map<String, Object> query, Map<String, Object> body) {
        return scope.run(actor, request -> switch (operation) {
            case DIAGNOSTICS -> runtime.runOpsDiagnostics((String) query.get("projectCode"), (String) query.get("status"),
                    null, null, (String) query.get("agentId"), null, (String) query.get("keyword"),
                    (Integer) query.get("limit"), (Integer) query.get("days"));
            case COMPARE -> runtime.runOpsCompare(id, secondId);
            case REPLAY -> runtime.runOpsReplay(id, body);
            case EVAL_DATASET_CREATE -> runtime.createEvalOpsDataset(request, body);
            case EVAL_DATASET_VERSION_CREATE -> runtime.createEvalOpsDatasetVersion(request, Long.valueOf(id), body);
            case EVAL_DATASET_VERSION_FROM_TRACE -> runtime.createEvalOpsDatasetVersionFromTrace(request, Long.valueOf(id), body);
            case EVAL_EXPERIMENT_CREATE -> runtime.createEvalOpsExperiment(request, body);
            case EVAL_EXPERIMENT_CANCEL -> runtime.cancelEvalOpsExperiment(Long.valueOf(id));
            case EVAL_EXPERIMENT_ITEMS -> runtime.listEvalOpsExperimentItems(Long.valueOf(id),
                    secondId == null ? null : Long.valueOf(secondId), (Integer) query.get("page"), (Integer) query.get("pageSize"));
        });
    }
}
