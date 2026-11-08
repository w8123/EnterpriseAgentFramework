package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityDescriptor;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.enterprise.ai.runtime.workflow.node.WorkflowNodeMaturity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.effectiveNodeConfig;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.firstText;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.text;

@Service
public class RuntimeWorkflowReleaseValidationService {

    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry;
    private final RuntimeWorkflowGraphValidator graphValidator = new RuntimeWorkflowGraphValidator();
    private final RuntimeWorkflowInteractionValidator interactionValidator = new RuntimeWorkflowInteractionValidator();
    private final RuntimeWorkflowNodeConfigValidator nodeConfigValidator = new RuntimeWorkflowNodeConfigValidator();
    private final RuntimeWorkflowResourceValidator resourceValidator;

    public RuntimeWorkflowReleaseValidationService(RuntimeControlCatalogClient controlCatalogClient,
                                                   ObjectMapper objectMapper) {
        this(controlCatalogClient, objectMapper, new RuntimeWorkflowNodeCapabilityRegistry(), null);
    }

    public RuntimeWorkflowReleaseValidationService(RuntimeControlCatalogClient controlCatalogClient,
                                                   ObjectMapper objectMapper,
                                                   RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry) {
        this(controlCatalogClient, objectMapper, nodeCapabilityRegistry, null);
    }

    @Autowired
    public RuntimeWorkflowReleaseValidationService(
            RuntimeControlCatalogClient controlCatalogClient,
            ObjectMapper objectMapper,
            RuntimeWorkflowNodeCapabilityRegistry nodeCapabilityRegistry,
            RuntimeWorkflowResourceBindingService resourceBindingService) {
        this.objectMapper = objectMapper;
        this.nodeCapabilityRegistry = nodeCapabilityRegistry == null
                ? new RuntimeWorkflowNodeCapabilityRegistry()
                : nodeCapabilityRegistry;
        this.resourceValidator = new RuntimeWorkflowResourceValidator(controlCatalogClient, resourceBindingService);
    }

    private RuntimePublishedWorkflowSnapshotQuery publishedSnapshots;

    @Autowired(required = false)
    public void setPublishedSnapshots(RuntimePublishedWorkflowSnapshotQuery publishedSnapshots) {
        this.publishedSnapshots = publishedSnapshots;
    }

    private static final java.util.Set<String> SUB_WORKFLOW_FORBIDDEN_CHILD_TYPES =
            java.util.Set.of("HUMAN_APPROVAL", "PAGE_ACTION", "SUB_WORKFLOW");

    /** The child must be a published version in the same project and must never pause or need a page. */
    private void validatePinnedSubWorkflow(RuntimeWorkflowDefinitionEntity workflow, GraphSpec.Node node,
                                           String workflowId, Long versionId,
                                           RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (publishedSnapshots == null) {
            report.warn("GRAPH_SUB_WORKFLOW_UNVERIFIED", node.getId(), "无法在发布时检查子流程版本");
            return;
        }
        RuntimePublishedWorkflowSnapshot snapshot;
        try {
            snapshot = publishedSnapshots.resolve(workflowId, versionId);
        } catch (RuntimeException ex) {
            report.error("GRAPH_SUB_WORKFLOW_NOT_FOUND", node.getId(), "子流程的发布版本不存在或已不可用，请重新选择");
            return;
        }
        if (StringUtils.hasText(workflow.getProjectCode()) && StringUtils.hasText(snapshot.projectCode())
                && !workflow.getProjectCode().equals(snapshot.projectCode())) {
            report.error("GRAPH_SUB_WORKFLOW_PROJECT_MISMATCH", node.getId(), "子流程必须属于同一个项目");
        }
        try {
            GraphSpec child = objectMapper.readValue(snapshot.graphSpecJson(), GraphSpec.class);
            for (GraphSpec.Node childNode : child.getNodes() == null ? java.util.List.<GraphSpec.Node>of() : child.getNodes()) {
                String childType = AgentGraphNodeType.normalize(childNode.getType());
                boolean blockingInteraction = "INTERACTION".equals(childType)
                        && !nodeCapabilityRegistry.isPublishable(childType, childNode.getConfig());
                if (SUB_WORKFLOW_FORBIDDEN_CHILD_TYPES.contains(childType) || blockingInteraction) {
                    report.error("GRAPH_SUB_WORKFLOW_CHILD_UNSUPPORTED", node.getId(),
                            "子流程里不能有人工审批、页面动作、交互暂停或再嵌套子流程：" + childNode.getId());
                    return;
                }
            }
        } catch (Exception ex) {
            report.error("GRAPH_SUB_WORKFLOW_NOT_FOUND", node.getId(), "子流程的发布版本无法读取");
        }
    }

    public RuntimeWorkflowReleaseValidationResult validate(RuntimeWorkflowDefinitionEntity workflow) {
        RuntimeWorkflowReleaseValidationResult.Builder report = RuntimeWorkflowReleaseValidationResult.builder();
        if (workflow == null) {
            report.error("WORKFLOW_NOT_FOUND", null, "Workflow does not exist");
            return report.build();
        }
        Set<String> boundPageKeys = resourceValidator.validateResourceBindings(workflow, report);
        GraphSpec graph = readGraph(workflow.getGraphSpecJson(), report);
        if (graph == null) {
            return report.build();
        }
        validateGraph(workflow, graph, boundPageKeys, report);
        validateWorkflowKindContract(workflow, graph, report);
        return report.build();
    }

    public RuntimeWorkflowReleaseValidationResult validateProposed(RuntimeWorkflowDefinitionEntity workflow,
                                                                  GraphSpec graphSpec) {
        RuntimeWorkflowReleaseValidationResult.Builder report = RuntimeWorkflowReleaseValidationResult.builder();
        if (workflow == null) {
            report.error("WORKFLOW_NOT_FOUND", null, "Workflow does not exist");
            return report.build();
        }
        Set<String> boundPageKeys = resourceValidator.validateResourceBindings(workflow, report);
        if (graphSpec == null) {
            report.error("GRAPH_SPEC_MISSING", null, "GraphSpec is required");
            return report.build();
        }
        validateGraph(workflow, graphSpec, boundPageKeys, report);
        validateWorkflowKindContract(workflow, graphSpec, report);
        return report.build();
    }

    /**
     * Workflow-kind constraints stay beside Runtime release validation so a
     * GraphSpec accepted through AI Coding cannot be rejected later by Studio
     * for a rule that was never exposed by the public contract.
     */
    private void validateWorkflowKindContract(
            RuntimeWorkflowDefinitionEntity workflow,
            GraphSpec graph,
            RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (workflow != null
                && WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())) {
            RuntimeWorkflowInputContract.validatePageAssistant(graph, report);
        }
    }

    public GraphSpec readGraph(String graphSpecJson, RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (!StringUtils.hasText(graphSpecJson)) {
            report.error("GRAPH_SPEC_MISSING", null, "Workflow publishing requires GraphSpec");
            return null;
        }
        try {
            GraphSpec graph = objectMapper.readValue(graphSpecJson, GraphSpec.class);
            if (graph == null) {
                report.error("GRAPH_SPEC_INVALID", null, "GraphSpec JSON must be an object");
                return null;
            }
            return graph;
        } catch (Exception ex) {
            report.error("GRAPH_SPEC_INVALID", null, "GraphSpec JSON is invalid: " + ex.getMessage());
            return null;
        }
    }

    private void validateGraph(RuntimeWorkflowDefinitionEntity workflow,
                               GraphSpec graph,
                               Set<String> boundPageKeys,
                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (!Integer.valueOf(2).equals(graph.getSchemaVersion())) {
            report.error("GRAPH_SCHEMA_VERSION_INVALID", null, "GraphSpec schemaVersion must be 2");
        }
        List<GraphSpec.Node> nodes = graph.getNodes() == null ? List.of() : graph.getNodes();
        if (nodes.isEmpty()) {
            report.error("GRAPH_NODE_EMPTY", null, "GraphSpec requires at least one node");
            return;
        }
        List<GraphSpec.Edge> edges = graph.getEdges() == null ? List.of() : graph.getEdges();
        Map<String, GraphSpec.Node> byId = new LinkedHashMap<>();
        Map<String, String> outputAliases = new LinkedHashMap<>();
        Map<String, RuntimeControlCatalogClient.PageActionCatalogEntry> pageActionsByNodeId =
                new LinkedHashMap<>();
        // First pass: index all nodes so LOOP body ownership checks see the full graph.
        for (GraphSpec.Node node : nodes) {
            if (node == null || !StringUtils.hasText(node.getId())) {
                report.error("GRAPH_NODE_ID_EMPTY", null, "GraphSpec node id is required");
                continue;
            }
            String nodeId = node.getId().trim();
            if (byId.putIfAbsent(nodeId, node) != null) {
                report.error("GRAPH_DUPLICATE_NODE_ID", nodeId, "Duplicate node id: " + nodeId);
            }
        }
        for (GraphSpec.Node node : byId.values()) {
            String nodeId = node.getId().trim();
            AgentGraphNodeType canonicalType = AgentGraphNodeType.find(node.getType()).orElse(null);
            boolean knownType = canonicalType != null && canonicalType.type().equals(node.getType());
            String type = knownType ? canonicalType.type() : String.valueOf(node.getType());
            RuntimeWorkflowNodeCapabilityDescriptor capability = nodeCapabilityRegistry.find(type).orElse(null);
            boolean runtimeExecutable = capability != null && capability.runtimeExecutable();
            // The capability registry applies variant-level openness. PRESENT_OUTPUT can publish
            // independently while blocking INTERACTION variants remain behind the pause/resume gate.
            boolean publishable = capability != null
                    && nodeCapabilityRegistry.isPublishable(type, node.getConfig());
            if (!knownType) {
                report.error("GRAPH_NODE_TYPE_UNSUPPORTED", nodeId,
                        "Graph node type must use a canonical value: " + node.getType());
            } else if (canonicalType == AgentGraphNodeType.TOOL
                    && node.getRef() != null
                    && StringUtils.hasText(node.getRef().getKind())
                    && !"TOOL".equals(node.getRef().getKind())) {
                report.error("GRAPH_TOOL_REF_KIND_INVALID", nodeId,
                        "GraphSpec TOOL node ref.kind must be TOOL");
            } else if (!runtimeExecutable) {
                report.error("GRAPH_NODE_RUNTIME_UNSUPPORTED", nodeId,
                        "Graph node type is known but not executable by the current Runtime: " + type);
            } else if (!publishable) {
                String reason = capability == null || !StringUtils.hasText(capability.unavailableReason())
                        ? ("Graph node type is not publishable: " + type)
                        : capability.unavailableReason();
                report.error("GRAPH_NODE_NOT_PUBLISHABLE", nodeId, reason);
                // LOOP structure must still fail-closed while BETA is closed (Debug/tests).
                if ("LOOP".equals(type)) {
                    graphValidator.validateLoopNode(node, byId, edges, report);
                    nodeConfigValidator.validateOutputAlias(node, outputAliases, report);
                    nodeConfigValidator.validateRetryPolicy(node, report);
                }
            } else {
                if (capability.maturity() == WorkflowNodeMaturity.BETA) {
                    String betaHint = switch (type) {
                        case "PAGE_ACTION" -> " depends on a valid project/page/action catalog and Page Bridge context";
                        case "KNOWLEDGE_RETRIEVAL" -> " depends on Knowledge service availability and ACL";
                        case "HTTP_REQUEST" -> " depends on egress policy, credentials and remote endpoint availability";
                        case "LOOP" -> " FOREACH/WHILE are bounded; Browser/Live LOOP E2E is still PENDING";
                        case "PARALLEL" -> " branches run on isolated variables and must meet at one join node";
                        case "CODE" -> " JavaScript runs in a sandbox without Java, network or files (3s default limit)";
                        case "SUB_WORKFLOW" -> " runs the pinned published version; the child cannot pause";
                        case "INTERACTION" -> " PRESENT_OUTPUT is display-only; blocking interaction variants remain closed";
                        default -> "";
                    };
                    report.warn("GRAPH_NODE_BETA", nodeId, "Node type " + type + " is BETA" + betaHint);
                }
                if ("INTENT_CLASSIFIER".equals(type)) {
                    Map<String, Object> config = effectiveNodeConfig(node, "classifierConfig");
                    nodeConfigValidator.validateIntentClassifier(node, config, edges, report);
                    if (nodeConfigValidator.classifierRequiresModelInstance(config)) {
                        resourceValidator.validateModelInstance(workflow, node, config, report);
                    }
                } else if ("IF_ELSE".equals(type)) {
                    nodeConfigValidator.validateConditionRoutes(node, effectiveNodeConfig(node, "conditionConfig"), edges, report);
                } else if ("LLM".equals(type)) {
                    resourceValidator.validateModelInstance(workflow, node, report);
                } else if ("PARAMETER_EXTRACT".equals(type)) {
                    Map<String, Object> config = effectiveNodeConfig(node, "parameterConfig");
                    nodeConfigValidator.validateParameterExtract(node, config, report);
                    String mode = firstText(text(config.get("extractMode")), text(config.get("mode")), "expression");
                    if ("LLM".equalsIgnoreCase(mode)) {
                        resourceValidator.validateModelInstance(workflow, node, config, report);
                    }
                } else if ("TOOL".equals(type)) {
                    nodeConfigValidator.validateToolReference(node, type, report);
                } else if ("INTERACTION".equals(type)) {
                    interactionValidator.validateInteractionNode(node, edges, report);
                } else if ("HUMAN_APPROVAL".equals(type)) {
                    if ((graph.getExitNodeIds() == null || !graph.getExitNodeIds().contains(nodeId))
                            && !RuntimeWorkflowValidationSupport.hasRouteEdge(edges, nodeId, "approved")) {
                        report.error("GRAPH_APPROVAL_APPROVED_ROUTE_REQUIRED", nodeId, "人工确认需要明确连接批准分支");
                    }
                    if (!RuntimeWorkflowValidationSupport.hasRouteEdge(edges, nodeId, "rejected")) {
                        report.warn("GRAPH_APPROVAL_REJECTED_ROUTE_MISSING", nodeId, "拒绝时流程将在此安全结束");
                    }
                } else if ("VARIABLE_ASSIGN".equals(type)) {
                    nodeConfigValidator.validateVariableAssign(node, report);
                } else if ("TEMPLATE".equals(type)) {
                    nodeConfigValidator.validateTemplateNode(node, report);
                } else if ("VARIABLE_AGGREGATOR".equals(type)) {
                    nodeConfigValidator.validateVariableAggregator(node, report);
                } else if ("KNOWLEDGE_RETRIEVAL".equals(type)) {
                    nodeConfigValidator.validateKnowledgeRetrieval(node, byId, edges, report);
                } else if ("HTTP_REQUEST".equals(type)) {
                    nodeConfigValidator.validateHttpRequest(node, report);
                } else if ("LOOP".equals(type)) {
                    graphValidator.validateLoopNode(node, byId, edges, report);
                } else if ("PARALLEL".equals(type)) {
                    graphValidator.validateParallelNode(node, byId, edges, graph.getExitNodeIds(), report);
                } else if ("CODE".equals(type)) {
                    nodeConfigValidator.validateCode(node, report);
                } else if ("LIST_OPERATOR".equals(type)) {
                    nodeConfigValidator.validateListOperator(node, report);
                } else if ("KNOWLEDGE_WRITE".equals(type)) {
                    nodeConfigValidator.validateKnowledgeWrite(node, report);
                    report.warn("GRAPH_KNOWLEDGE_WRITE", nodeId, "该节点会写入知识库；发布到员工 MCP 时按写操作处理");
                } else if ("SUB_WORKFLOW".equals(type)) {
                    String[] pin = nodeConfigValidator.validateSubWorkflowReference(node, workflow.getId(), report);
                    if (pin != null) validatePinnedSubWorkflow(workflow, node, pin[0], Long.parseLong(pin[1]), report);
                }
                if ("PAGE_ACTION".equals(type)) {
                    RuntimeControlCatalogClient.PageActionCatalogEntry action =
                            resourceValidator.validatePageActionNode(workflow, node, boundPageKeys, report);
                    if (action != null) {
                        pageActionsByNodeId.put(nodeId, action);
                    }
                }
                nodeConfigValidator.validateOutputAlias(node, outputAliases, report);
                nodeConfigValidator.validateRetryPolicy(node, report);
            }
        }

        for (GraphSpec.Node node : byId.values()) {
            nodeConfigValidator.validateErrorPolicy(node, byId.keySet(), report);
        }
        resourceValidator.validatePageActionOutputReferences(byId, pageActionsByNodeId, report);
        graphValidator.validateUserInputFieldReferences(byId, report);
        graphValidator.validateLoopBodyDualOwnership(byId, report);

        graphValidator.validateBoundariesAndEdges(graph, byId, edges, report);
    }

}
