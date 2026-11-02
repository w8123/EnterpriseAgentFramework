package com.enterprise.ai.control.pageworkbench.api;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsolePermissionPolicy;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPageWorkflowDeliveryPort;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowDeliveryApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowDeliveryApplicationService.DeliveryCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Applies the console's project route rule and the delivery publish check before planning or delivering. */
@Component
@RequiredArgsConstructor
class PageWorkflowDeliveryMcpPlatformAdapter implements McpPlatformPageWorkflowDeliveryPort {
    private final PageWorkbenchConsoleController workbench;
    private final PageWorkbenchWorkflowDeliveryApplicationService deliveries;
    private final PlatformDelegatedRequestScope scope;

    @Override
    public Map<String, Object> plan(PlatformAuthenticatedSession actor, String projectCode, String taskId, Map<String, Object> command) {
        DeliveryCommand value = command(actor, command);
        admit(actor, projectCode, taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        scope.run(actor, request -> {
            PageWorkbenchConsoleController.requireDeliveryPermission(request, projectCode, value);
            var plan = deliveries.plan(projectCode, taskId, value);
            result.put("workflowId", plan.workflowId());
            result.put("artifactId", plan.artifactId());
            result.put("request", plan.request());
            return ResponseEntity.ok().build();
        });
        return result;
    }

    @Override
    public ResponseEntity<?> deliver(PlatformAuthenticatedSession actor, String projectCode, String taskId, Map<String, Object> command) {
        DeliveryCommand value = command(actor, command);
        admit(actor, projectCode, taskId);
        return scope.run(actor, request -> workbench.deliverWorkflow(request, projectCode, taskId, value));
    }

    private static DeliveryCommand command(PlatformAuthenticatedSession actor, Map<String, Object> command) {
        if (actor == null || actor.user() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return new DeliveryCommand(text(command, "pageKey"), text(command, "version"), text(command, "agentId"),
                text(command, "modelInstanceId"), actor.user().getUsername(), text(command, "replaceWorkflowId"));
    }

    private static void admit(PlatformAuthenticatedSession actor, String projectCode, String taskId) {
        String path = "/api/registry/projects/" + projectCode + "/page-workbench/workflow-engineering/tasks/" + taskId + "/deliver";
        var rule = PlatformConsolePermissionPolicy.ruleFor(path).orElseThrow();
        if (!PlatformConsolePermissionPolicy.admits(rule, "POST", path, actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "platform write permission on the project is required");
        }
    }

    private static String text(Map<String, Object> command, String key) {
        Object value = command == null ? null : command.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }
}
