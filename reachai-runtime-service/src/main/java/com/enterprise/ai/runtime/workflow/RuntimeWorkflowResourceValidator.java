package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import feign.FeignException;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.firstText;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.mapValue;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.text;

/** Validates release resource bindings, model selection and page-action catalog contracts. */
final class RuntimeWorkflowResourceValidator {

    private static final Pattern PAGE_ACTION_DATA_WRAPPER_REFERENCE = Pattern.compile(
            "nodeOutput\\.([A-Za-z0-9_-]+)\\.data(?=\\.|\\b)");

    private final RuntimeControlCatalogClient controlCatalogClient;
    private final RuntimeWorkflowResourceBindingService resourceBindingService;

    RuntimeWorkflowResourceValidator(RuntimeControlCatalogClient controlCatalogClient,
                                     RuntimeWorkflowResourceBindingService resourceBindingService) {
        this.controlCatalogClient = controlCatalogClient;
        this.resourceBindingService = resourceBindingService;
    }

    Set<String> validateResourceBindings(
                                         RuntimeWorkflowDefinitionEntity workflow,
                                         RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (!WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())) {
            return Set.of();
        }
        if (resourceBindingService == null) {
            report.error(
                    "PAGE_RESOURCE_BINDING_UNAVAILABLE",
                    null,
                    "PAGE_ASSISTANT release validation cannot read Workflow resource bindings");
            return Set.of();
        }
        List<RuntimeWorkflowResourceBindingService.BindingView> bindings =
                resourceBindingService.list(workflow.getId());
        long targetPages = bindings.stream()
                .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equals(binding.resourceType()))
                .filter(binding -> RuntimeWorkflowResourceBindingService.ROLE_TARGET
                        .equals(binding.bindingRole()))
                .count();
        if (targetPages != 1) {
            report.error(
                    "PAGE_RESOURCE_BINDING_INVALID",
                    null,
                    "PAGE_ASSISTANT workflow requires exactly one active TARGET PAGE resource binding");
        }
        return bindings.stream()
                .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equals(binding.resourceType()))
                .map(RuntimeWorkflowResourceBindingService.BindingView::resourceKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    void validateModelInstance(RuntimeWorkflowDefinitionEntity workflow,
                               GraphSpec.Node node,
                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        validateModelInstance(workflow, node, node.getConfig() == null ? Map.of() : node.getConfig(), report);
    }

    void validateModelInstance(RuntimeWorkflowDefinitionEntity workflow,
                               GraphSpec.Node node,
                               Map<String, Object> config,
                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        String nodeModelInstanceId = text(config.get("modelInstanceId"));
        String workflowModelInstanceId = workflow == null ? null : workflow.getDefaultModelInstanceId();
        if (!StringUtils.hasText(nodeModelInstanceId) && !StringUtils.hasText(workflowModelInstanceId)) {
            report.error("GRAPH_MODEL_INSTANCE_REQUIRED", node.getId(),
                    "Model node requires modelInstanceId on node config or Workflow default model instance");
        }
    }

    RuntimeControlCatalogClient.PageActionCatalogEntry validatePageActionNode(
                                                                              RuntimeWorkflowDefinitionEntity workflow,
                                                                              GraphSpec.Node node,
                                                                              Set<String> boundPageKeys,
                                                                              RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String pageKey = text(config.get("pageKey"));
        String actionKey = text(config.get("actionKey"));
        String projectCode = firstText(text(config.get("projectCode")), workflow.getProjectCode());
        if (!StringUtils.hasText(pageKey)) {
            report.error("GRAPH_PAGE_ACTION_PAGE_KEY_EMPTY", node.getId(), "PAGE_ACTION node requires pageKey");
        }
        if (!StringUtils.hasText(actionKey)) {
            report.error("GRAPH_PAGE_ACTION_KEY_EMPTY", node.getId(), "PAGE_ACTION node requires actionKey");
        }
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(pageKey) || !StringUtils.hasText(actionKey)) {
            return null;
        }
        if (WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())
                && !boundPageKeys.contains(pageKey)) {
            report.error(
                    "GRAPH_PAGE_ACTION_PAGE_UNBOUND",
                    node.getId(),
                    "PAGE_ACTION pageKey is not bound to this Workflow: " + pageKey);
        }

        RuntimeControlCatalogClient.PageActionCatalogEntry action;
        try {
            action = controlCatalogClient.getPageAction(projectCode, pageKey, actionKey);
        } catch (FeignException.NotFound ex) {
            action = null;
        }
        if (action == null) {
            report.error("GRAPH_PAGE_ACTION_CATALOG_MISSING", node.getId(),
                    "PAGE_ACTION catalog entry does not exist: " + projectCode + "/" + pageKey + "/" + actionKey);
            return null;
        }
        if (!"ACTIVE".equalsIgnoreCase(action.status())) {
            report.error("GRAPH_PAGE_ACTION_CATALOG_INACTIVE", node.getId(),
                    "PAGE_ACTION catalog entry is not ACTIVE: " + projectCode + "/" + pageKey + "/" + actionKey);
        }
        Map<String, Object> inputSchema = mapValue(action.inputSchema());
        Map<String, Object> args = mapValue(config.get("args"));
        Object requiredValue = inputSchema.get("required");
        if (requiredValue instanceof Iterable<?> requiredFields) {
            List<String> missing = new ArrayList<>();
            for (Object requiredField : requiredFields) {
                String field = text(requiredField);
                if (StringUtils.hasText(field) && !args.containsKey(field)) {
                    missing.add(field);
                }
            }
            if (!missing.isEmpty()) {
                report.error(
                        "GRAPH_PAGE_ACTION_ARGS_REQUIRED_MISSING",
                        node.getId(),
                        "PAGE_ACTION args are missing required fields: " + String.join(", ", missing));
            }
        }
        if (action.confirmRequired()) {
            report.warn(
                    "GRAPH_PAGE_ACTION_CONFIRM_REQUIRED",
                    node.getId(),
                    "PAGE_ACTION requires explicit runtime confirmation: " + actionKey);
        }
        return action;
    }

    /**
     * Page Bridge removes the action result envelope before publishing a PAGE_ACTION node output:
     * {@code nodeOutput.<nodeId>} is the business action's declared {@code data} value itself.
     * A second {@code .data} silently resolves to null unless that property is explicitly part of
     * the business output schema, so fail the release while the mistake is still actionable.
     */
    void validatePageActionOutputReferences(
                                            Map<String, GraphSpec.Node> nodesById,
                                            Map<String, RuntimeControlCatalogClient.PageActionCatalogEntry> pageActionsByNodeId,
                                            RuntimeWorkflowReleaseValidationResult.Builder report) {
        for (GraphSpec.Node consumer : nodesById.values()) {
            validatePageActionOutputReferences(
                    consumer.getConfig(),
                    consumer.getId(),
                    pageActionsByNodeId,
                    report);
        }
    }

    private void validatePageActionOutputReferences(
            Object value,
                                            String consumerNodeId,
                                            Map<String, RuntimeControlCatalogClient.PageActionCatalogEntry> pageActionsByNodeId,
                                            RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                validatePageActionOutputReferences(nested, consumerNodeId, pageActionsByNodeId, report);
            }
            return;
        }
        if (value instanceof Iterable<?> items) {
            for (Object nested : items) {
                validatePageActionOutputReferences(nested, consumerNodeId, pageActionsByNodeId, report);
            }
            return;
        }
        if (!(value instanceof String expression) || !expression.contains("nodeOutput.")) {
            return;
        }
        Matcher matcher = PAGE_ACTION_DATA_WRAPPER_REFERENCE.matcher(expression);
        while (matcher.find()) {
            String producerNodeId = matcher.group(1);
            RuntimeControlCatalogClient.PageActionCatalogEntry action =
                    pageActionsByNodeId.get(producerNodeId);
            if (action == null || declaresRootProperty(action.outputSchema(), "data")) {
                continue;
            }
            String corrected = expression.substring(0, matcher.start())
                    + "nodeOutput." + producerNodeId
                    + expression.substring(matcher.end());
            report.error(
                    "GRAPH_PAGE_ACTION_OUTPUT_DATA_REDUNDANT",
                    consumerNodeId,
                    "PAGE_ACTION nodeOutput already is the business action data. Use "
                            + corrected + " instead of adding another .data after nodeOutput."
                            + producerNodeId);
        }
    }

    private boolean declaresRootProperty(Object schemaValue, String propertyName) {
        Map<String, Object> schema = mapValue(schemaValue);
        Map<String, Object> properties = mapValue(schema.get("properties"));
        return properties.containsKey(propertyName);
    }
}
