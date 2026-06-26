package com.enterprise.ai.agent.workflow.aicoding.pageassistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryMapper;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.aicoding.WorkflowAiCodingAuthService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WorkflowPageAssistantAiCodingService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowAiCodingAuthService aiCodingAuthService;
    private final WorkflowRuntimeGraphAdapter workflowRuntimeGraphAdapter;
    private final PageActionRegistryMapper pageActionRegistryMapper;
    private final AgentWorkflowBindingService bindingService;
    private final ObjectMapper objectMapper;

    public WorkflowPageAssistantCatalogResponse getCatalog(String workflowId) {
        ResolvedWorkflow resolved = resolvePageAssistantWorkflow(workflowId, null);
        List<PageActionRegistryEntity> catalogEntities = loadCatalogActions(
                resolved.workflow().getProjectCode(),
                resolved.pageKey());
        List<WorkflowPageAssistantCatalogResponse.CatalogActionView> catalogActions =
                catalogEntities.stream().map(this::toCatalogActionView).toList();
        Map<String, PageActionRegistryEntity> catalogByKey = indexCatalog(catalogEntities);

        List<WorkflowPageAssistantCatalogResponse.PageActionNodeView> nodes = new ArrayList<>();
        for (GraphSpec.Node node : pageActionNodes(resolved.graphSpec())) {
            nodes.add(buildNodeView(node, resolved, catalogByKey));
        }

        List<String> warnings = new ArrayList<>(resolved.warnings());
        if (nodes.isEmpty()) {
            warnings.add("GraphSpec has no PAGE_ACTION nodes");
        }
        if (catalogActions.isEmpty()) {
            warnings.add("Page action catalog is empty for pageKey=" + resolved.pageKey());
        }

        return WorkflowPageAssistantCatalogResponse.builder()
                .workflowId(resolved.workflow().getId())
                .workflowType(resolved.workflow().getWorkflowType())
                .projectId(resolved.workflow().getProjectId())
                .projectCode(resolved.workflow().getProjectCode())
                .pageKey(resolved.pageKey())
                .routePattern(resolved.routePattern())
                .pageActionNodes(nodes)
                .catalogActions(catalogActions)
                .warnings(warnings)
                .build();
    }

    public WorkflowPageAssistantValidateResponse validate(String workflowId,
                                                          WorkflowPageAssistantValidateRequest request) {
        GraphSpec graphSpec = request == null ? null : request.getGraphSpec();
        ResolvedWorkflow resolved = resolvePageAssistantWorkflow(workflowId, graphSpec);
        Map<String, PageActionRegistryEntity> catalogByKey = indexCatalog(loadCatalogActions(
                resolved.workflow().getProjectCode(),
                resolved.pageKey()));

        List<WorkflowPageAssistantValidateResponse.Item> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>(resolved.warnings());
        boolean valid = true;

        for (GraphSpec.Node node : pageActionNodes(resolved.graphSpec())) {
            WorkflowPageAssistantValidateResponse.Item item = validateNode(node, resolved, catalogByKey);
            items.add(item);
            if (item.getErrors() != null && !item.getErrors().isEmpty()) {
                valid = false;
            }
            if (item.getWarnings() != null && !item.getWarnings().isEmpty()) {
                warnings.addAll(item.getWarnings().stream().map(WorkflowPageAssistantValidateResponse.Finding::getMessage).toList());
            }
        }
        if (items.isEmpty()) {
            warnings.add("GraphSpec has no PAGE_ACTION nodes to validate");
        }

        return WorkflowPageAssistantValidateResponse.builder()
                .workflowId(resolved.workflow().getId())
                .workflowType(resolved.workflow().getWorkflowType())
                .pageKey(resolved.pageKey())
                .valid(valid)
                .items(items)
                .warnings(warnings)
                .build();
    }

    public WorkflowPageAssistantSmokeTestResponse smokeTest(String workflowId,
                                                            WorkflowPageAssistantSmokeTestRequest request) {
        ResolvedWorkflow resolved = resolvePageAssistantWorkflow(workflowId, null);
        boolean dryRun = request == null || request.getDryRun() == null || Boolean.TRUE.equals(request.getDryRun());
        Map<String, Object> runtimeContext = request == null || request.getRuntimeContext() == null
                ? Map.of()
                : request.getRuntimeContext();
        Map<String, Object> runtimeVerification = request == null ? null : request.getRuntimeVerification();

        Map<String, PageActionRegistryEntity> catalogByKey = indexCatalog(loadCatalogActions(
                resolved.workflow().getProjectCode(),
                resolved.pageKey()));

        boolean bridgePresent = hasBridgeContext(runtimeContext);
        boolean runtimePassEvidence = hasRuntimeVerificationPass(runtimeVerification);
        List<String> warnings = new ArrayList<>(resolved.warnings());
        List<String> errors = new ArrayList<>();
        List<WorkflowPageAssistantSmokeTestResponse.NodeResult> nodeResults = new ArrayList<>();

        WorkflowPageAssistantValidateResponse validation = validate(workflowId,
                WorkflowPageAssistantValidateRequest.builder().graphSpec(resolved.graphSpec()).build());

        for (WorkflowPageAssistantValidateResponse.Item item : validation.getItems()) {
            nodeResults.add(evaluateSmokeNode(item, catalogByKey, dryRun, bridgePresent, runtimePassEvidence, warnings));
        }

        if (validation.getItems().isEmpty()) {
            warnings.add("GraphSpec has no PAGE_ACTION nodes to smoke-test");
        }
        if (!validation.isValid()) {
            errors.add("PAGE_ACTION validation failed; smoke-test cannot proceed to queue/runtime evidence");
        }
        if (!bridgePresent) {
            warnings.add("Missing embedSessionId/pageBridge/pageContext/bridgeGlobal; smoke-test stays SKIPPED");
        }
        if (bridgePresent && !runtimePassEvidence) {
            warnings.add("No runtimeVerification.browserRuntime.status=PASS evidence; cannot claim real page PASS");
        }

        String status = resolveSmokeStatus(dryRun, bridgePresent, runtimePassEvidence, validation.isValid(), nodeResults);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", resolved.workflow().getId());
        metadata.put("pageKey", resolved.pageKey());
        metadata.put("pageActionNodeCount", nodeResults.size());
        metadata.put("bridgeContextPresent", bridgePresent);
        metadata.put("validationValid", validation.isValid());

        return WorkflowPageAssistantSmokeTestResponse.builder()
                .status(status)
                .dryRun(dryRun)
                .bridgeContextPresent(bridgePresent)
                .runtimeVerificationStatus(runtimePassEvidence ? "PASS" : "NONE")
                .nodes(nodeResults)
                .warnings(warnings)
                .errors(errors)
                .metadata(metadata)
                .build();
    }

    private WorkflowPageAssistantSmokeTestResponse.NodeResult evaluateSmokeNode(
            WorkflowPageAssistantValidateResponse.Item item,
            Map<String, PageActionRegistryEntity> catalogByKey,
            boolean dryRun,
            boolean bridgePresent,
            boolean runtimePassEvidence,
            List<String> warnings) {
        if (item.getErrors() != null && !item.getErrors().isEmpty()) {
            return WorkflowPageAssistantSmokeTestResponse.NodeResult.builder()
                    .nodeId(item.getNodeId())
                    .actionKey(item.getActionKey())
                    .matchStatus(item.getMatchStatus())
                    .status(WorkflowPageAssistantSmokeTestResponse.NodeStatus.INVALID.name())
                    .message("validation errors present")
                    .build();
        }
        PageActionRegistryEntity catalog = catalogByKey.get(item.getActionKey());
        if (catalog != null && Boolean.TRUE.equals(catalog.getConfirmRequired()) && !dryRun) {
            return WorkflowPageAssistantSmokeTestResponse.NodeResult.builder()
                    .nodeId(item.getNodeId())
                    .actionKey(item.getActionKey())
                    .matchStatus(item.getMatchStatus())
                    .status(WorkflowPageAssistantSmokeTestResponse.NodeStatus.NEED_CONFIRM.name())
                    .message("confirmRequired action requires explicit confirmation policy before execution")
                    .build();
        }
        if (catalog != null && Boolean.TRUE.equals(catalog.getConfirmRequired())) {
            warnings.add("Node " + item.getNodeId() + " action " + item.getActionKey() + " is confirmRequired");
        }
        if (dryRun) {
            return WorkflowPageAssistantSmokeTestResponse.NodeResult.builder()
                    .nodeId(item.getNodeId())
                    .actionKey(item.getActionKey())
                    .matchStatus(item.getMatchStatus())
                    .status(WorkflowPageAssistantSmokeTestResponse.NodeStatus.DRY_RUN.name())
                    .message("dryRun=true: schema/catalog/bridge evidence checked only")
                    .build();
        }
        if (!bridgePresent) {
            return WorkflowPageAssistantSmokeTestResponse.NodeResult.builder()
                    .nodeId(item.getNodeId())
                    .actionKey(item.getActionKey())
                    .matchStatus(item.getMatchStatus())
                    .status(WorkflowPageAssistantSmokeTestResponse.NodeStatus.SKIPPED.name())
                    .message("bridge runtime context missing")
                    .build();
        }
        if (runtimePassEvidence) {
            return WorkflowPageAssistantSmokeTestResponse.NodeResult.builder()
                    .nodeId(item.getNodeId())
                    .actionKey(item.getActionKey())
                    .matchStatus(item.getMatchStatus())
                    .status(WorkflowPageAssistantSmokeTestResponse.NodeStatus.RUNTIME_PASS.name())
                    .message("runtimeVerification evidence indicates browser PASS")
                    .build();
        }
        return WorkflowPageAssistantSmokeTestResponse.NodeResult.builder()
                .nodeId(item.getNodeId())
                .actionKey(item.getActionKey())
                .matchStatus(item.getMatchStatus())
                .status(WorkflowPageAssistantSmokeTestResponse.NodeStatus.READY_TO_QUEUE.name())
                .message("bridge context present; action would queue client-side execution only")
                .build();
    }

    private String resolveSmokeStatus(boolean dryRun,
                                      boolean bridgePresent,
                                      boolean runtimePassEvidence,
                                      boolean validationValid,
                                      List<WorkflowPageAssistantSmokeTestResponse.NodeResult> nodeResults) {
        if (!validationValid) {
            return "INVALID";
        }
        if (dryRun) {
            return "DRY_RUN";
        }
        if (!bridgePresent) {
            return "SKIPPED";
        }
        boolean allRuntimePass = !nodeResults.isEmpty()
                && nodeResults.stream().allMatch(node -> "RUNTIME_PASS".equals(node.getStatus()));
        if (runtimePassEvidence && allRuntimePass) {
            return "RUNTIME_PASS";
        }
        boolean anyNeedConfirm = nodeResults.stream().anyMatch(node -> "NEED_CONFIRM".equals(node.getStatus()));
        if (anyNeedConfirm) {
            return "NEED_CONFIRM";
        }
        return "READY_TO_QUEUE";
    }

    private WorkflowPageAssistantValidateResponse.Item validateNode(GraphSpec.Node node,
                                                                  ResolvedWorkflow resolved,
                                                                  Map<String, PageActionRegistryEntity> catalogByKey) {
        NodeConfig config = readNodeConfig(node);
        MatchEvaluation match = evaluateMatch(config, resolved, catalogByKey);

        List<WorkflowPageAssistantValidateResponse.Finding> errors = new ArrayList<>();
        List<WorkflowPageAssistantValidateResponse.Finding> warnings = new ArrayList<>();

        switch (match.status()) {
            case PAGE_KEY_EMPTY -> errors.add(finding("PAGE_KEY_EMPTY", "ERROR", "pageKey",
                    "PAGE_ACTION node requires pageKey"));
            case ACTION_KEY_EMPTY -> errors.add(finding("ACTION_KEY_EMPTY", "ERROR", "actionKey",
                    "PAGE_ACTION node requires actionKey"));
            case PAGE_KEY_MISMATCH -> errors.add(finding("PAGE_KEY_MISMATCH", "ERROR", "pageKey",
                    "Node pageKey does not match workflow pageKey: " + config.pageKey()));
            case MISSING -> errors.add(finding("CATALOG_MISSING", "ERROR", "actionKey",
                    "Catalog entry not found: " + config.actionKey()));
            case INACTIVE -> errors.add(finding("CATALOG_INACTIVE", "ERROR", "actionKey",
                    "Catalog entry is not ACTIVE: " + config.actionKey()));
            case MATCHED -> {
                errors.addAll(validateRequiredArgs(config, match.catalog()));
                if (match.catalog() != null && Boolean.TRUE.equals(match.catalog().getConfirmRequired())) {
                    warnings.add(finding("CONFIRM_REQUIRED", "WARN", "actionKey",
                            "Action requires user confirmation; smoke-test/run cannot assume real execution"));
                }
            }
        }

        return WorkflowPageAssistantValidateResponse.Item.builder()
                .nodeId(node.getId())
                .matchStatus(match.status())
                .actionKey(config.actionKey())
                .pageKey(config.pageKey())
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    private List<WorkflowPageAssistantValidateResponse.Finding> validateRequiredArgs(
            NodeConfig config,
            PageActionRegistryEntity catalog) {
        List<WorkflowPageAssistantValidateResponse.Finding> errors = new ArrayList<>();
        if (catalog == null) {
            return errors;
        }
        Map<String, Object> inputSchema = readJsonMap(catalog.getInputSchemaJson());
        List<String> required = readRequiredFields(inputSchema);
        Map<String, Object> args = config.args() == null ? Map.of() : config.args();
        for (String field : required) {
            Object value = args.get(field);
            if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
                errors.add(finding("ARGS_REQUIRED_MISSING", "ERROR", field,
                        "config.args missing required field from inputSchema: " + field));
            }
        }
        return errors;
    }

    private WorkflowPageAssistantCatalogResponse.PageActionNodeView buildNodeView(
            GraphSpec.Node node,
            ResolvedWorkflow resolved,
            Map<String, PageActionRegistryEntity> catalogByKey) {
        NodeConfig config = readNodeConfig(node);
        MatchEvaluation match = evaluateMatch(config, resolved, catalogByKey);
        return WorkflowPageAssistantCatalogResponse.PageActionNodeView.builder()
                .nodeId(node.getId())
                .nodeType(AgentGraphNodeType.normalize(node.getType()))
                .pageKey(config.pageKey())
                .actionKey(config.actionKey())
                .args(config.args())
                .outputAlias(config.outputAlias())
                .matchStatus(match.status())
                .matchMessage(match.message())
                .build();
    }

    private MatchEvaluation evaluateMatch(NodeConfig config,
                                          ResolvedWorkflow resolved,
                                          Map<String, PageActionRegistryEntity> catalogByKey) {
        if (!StringUtils.hasText(config.pageKey())) {
            return MatchEvaluation.of(WorkflowPageAssistantCatalogResponse.MatchStatus.PAGE_KEY_EMPTY,
                    "pageKey is empty");
        }
        if (!StringUtils.hasText(config.actionKey())) {
            return MatchEvaluation.of(WorkflowPageAssistantCatalogResponse.MatchStatus.ACTION_KEY_EMPTY,
                    "actionKey is empty");
        }
        if (StringUtils.hasText(resolved.pageKey())
                && !resolved.pageKey().equalsIgnoreCase(config.pageKey())) {
            return MatchEvaluation.of(WorkflowPageAssistantCatalogResponse.MatchStatus.PAGE_KEY_MISMATCH,
                    "node pageKey differs from workflow pageKey");
        }
        PageActionRegistryEntity catalog = catalogByKey.get(config.actionKey());
        if (catalog == null) {
            return MatchEvaluation.of(WorkflowPageAssistantCatalogResponse.MatchStatus.MISSING,
                    "catalog entry not found");
        }
        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(catalog.getStatus()))) {
            return MatchEvaluation.of(WorkflowPageAssistantCatalogResponse.MatchStatus.INACTIVE,
                    "catalog entry status=" + catalog.getStatus(), catalog);
        }
        return MatchEvaluation.of(WorkflowPageAssistantCatalogResponse.MatchStatus.MATCHED,
                "catalog entry matched", catalog);
    }

    private ResolvedWorkflow resolvePageAssistantWorkflow(String workflowId, GraphSpec graphSpecOverride) {
        WorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        aiCodingAuthService.requireAiCodingKeyForWorkflow(workflow);
        List<String> warnings = new ArrayList<>();
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(String.valueOf(workflow.getWorkflowType()))) {
            throw new IllegalArgumentException("workflow type must be PAGE_ASSISTANT, got: " + workflow.getWorkflowType());
        }
        PageContext pageContext = resolvePageContext(workflow);
        GraphSpec graphSpec = graphSpecOverride != null
                ? graphSpecOverride
                : readGraphSpec(workflow, warnings);
        return new ResolvedWorkflow(workflow, pageContext.pageKey(), pageContext.routePattern(), graphSpec, warnings);
    }

    private PageContext resolvePageContext(WorkflowDefinitionEntity workflow) {
        Map<String, Object> extra = readJsonMap(workflow.getExtraJson());
        Map<String, Object> pageAssistantExtra = pageAssistantExtra(extra);
        String pageKey = firstText(text(extra.get("pageKey")), text(pageAssistantExtra.get("pageKey")));
        String routePattern = firstText(text(extra.get("routePattern")), text(pageAssistantExtra.get("routePattern")));
        for (AgentWorkflowBindingEntity binding : bindingService.listByWorkflowId(workflow.getId())) {
            if (!StringUtils.hasText(pageKey) && StringUtils.hasText(binding.getPageKey())) {
                pageKey = binding.getPageKey();
            }
            if (!StringUtils.hasText(routePattern) && StringUtils.hasText(binding.getRoutePattern())) {
                routePattern = binding.getRoutePattern();
            }
        }
        return new PageContext(pageKey, routePattern);
    }

    private GraphSpec readGraphSpec(WorkflowDefinitionEntity workflow, List<String> warnings) {
        if (!StringUtils.hasText(workflow.getGraphSpecJson())) {
            warnings.add("GraphSpec JSON is empty");
            return GraphSpec.builder().build();
        }
        try {
            return workflowRuntimeGraphAdapter.readGraphSpec(workflow.getGraphSpecJson());
        } catch (Exception ex) {
            throw new IllegalArgumentException("graphSpecJson is invalid: " + ex.getMessage(), ex);
        }
    }

    private List<GraphSpec.Node> pageActionNodes(GraphSpec graphSpec) {
        if (graphSpec == null || graphSpec.getNodes() == null) {
            return List.of();
        }
        List<GraphSpec.Node> nodes = new ArrayList<>();
        for (GraphSpec.Node node : graphSpec.getNodes()) {
            if (node != null && "PAGE_ACTION".equals(AgentGraphNodeType.normalize(node.getType()))) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private List<PageActionRegistryEntity> loadCatalogActions(String projectCode, String pageKey) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(pageKey)) {
            return List.of();
        }
        return pageActionRegistryMapper.selectList(new LambdaQueryWrapper<PageActionRegistryEntity>()
                .eq(PageActionRegistryEntity::getProjectCode, projectCode)
                .eq(PageActionRegistryEntity::getPageKey, pageKey)
                .ne(PageActionRegistryEntity::getStatus, "REMOVED")
                .orderByAsc(PageActionRegistryEntity::getActionKey));
    }

    private Map<String, PageActionRegistryEntity> indexCatalog(List<PageActionRegistryEntity> actions) {
        Map<String, PageActionRegistryEntity> indexed = new LinkedHashMap<>();
        if (actions == null) {
            return indexed;
        }
        for (PageActionRegistryEntity action : actions) {
            if (action != null && StringUtils.hasText(action.getActionKey())) {
                indexed.putIfAbsent(action.getActionKey().trim(), action);
            }
        }
        return indexed;
    }

    private WorkflowPageAssistantCatalogResponse.CatalogActionView toCatalogActionView(PageActionRegistryEntity entity) {
        return WorkflowPageAssistantCatalogResponse.CatalogActionView.builder()
                .actionKey(entity.getActionKey())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .confirmRequired(entity.getConfirmRequired())
                .inputSchema(readJsonMap(entity.getInputSchemaJson()))
                .outputSchema(readJsonMap(entity.getOutputSchemaJson()))
                .sampleArgs(readJsonMap(entity.getSampleArgsJson()))
                .metadata(readJsonMap(entity.getMetadataJson()))
                .build();
    }

    private NodeConfig readNodeConfig(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> args = null;
        Object rawArgs = config.get("args");
        if (rawArgs instanceof Map<?, ?> map) {
            args = objectMapper.convertValue(map, MAP_TYPE);
        }
        String outputAlias = firstText(text(config.get("outputAlias")), text(node.getName()));
        return new NodeConfig(
                text(config.get("pageKey")),
                firstText(text(config.get("actionKey")), text(config.get("action"))),
                args,
                outputAlias);
    }

    @SuppressWarnings("unchecked")
    private List<String> readRequiredFields(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            return List.of();
        }
        Object required = inputSchema.get("required");
        if (!(required instanceof List<?> list)) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (Object item : list) {
            if (item != null && StringUtils.hasText(String.valueOf(item))) {
                fields.add(String.valueOf(item).trim());
            }
        }
        return fields;
    }

    private Map<String, Object> readJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Map<String, Object> pageAssistantExtra(Map<String, Object> extra) {
        if (extra == null || !(extra.get("pageAssistant") instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return objectMapper.convertValue(raw, MAP_TYPE);
    }

    private boolean hasBridgeContext(Map<String, Object> runtimeContext) {
        if (runtimeContext == null || runtimeContext.isEmpty()) {
            return false;
        }
        if (hasNonBlankValue(runtimeContext.get("embedSessionId"))) {
            return true;
        }
        if (runtimeContext.get("pageBridge") instanceof Map<?, ?> bridge && !bridge.isEmpty()) {
            return true;
        }
        if (runtimeContext.get("pageContext") instanceof Map<?, ?> pageContext && !pageContext.isEmpty()) {
            return true;
        }
        return hasNonBlankValue(runtimeContext.get("bridgeGlobal"));
    }

    @SuppressWarnings("unchecked")
    private boolean hasRuntimeVerificationPass(Map<String, Object> runtimeVerification) {
        if (runtimeVerification == null || runtimeVerification.isEmpty()) {
            return false;
        }
        Object browserRuntime = runtimeVerification.get("browserRuntime");
        if (browserRuntime instanceof Map<?, ?> map) {
            Object status = map.get("status");
            return "PASS".equalsIgnoreCase(String.valueOf(status));
        }
        return "PASS".equalsIgnoreCase(String.valueOf(runtimeVerification.get("status")));
    }

    private WorkflowDefinitionEntity requireWorkflow(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflowId is required");
        }
        return workflowDefinitionService.findById(workflowId.trim())
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
    }

    private WorkflowPageAssistantValidateResponse.Finding finding(String code,
                                                                  String level,
                                                                  String field,
                                                                  String message) {
        return WorkflowPageAssistantValidateResponse.Finding.builder()
                .code(code)
                .level(level)
                .field(field)
                .message(message)
                .build();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : fallback;
    }

    private boolean hasNonBlankValue(Object value) {
        return value != null && StringUtils.hasText(String.valueOf(value));
    }

    private record PageContext(String pageKey, String routePattern) {
    }

    private record NodeConfig(String pageKey, String actionKey, Map<String, Object> args, String outputAlias) {
    }

    private record ResolvedWorkflow(WorkflowDefinitionEntity workflow,
                                    String pageKey,
                                    String routePattern,
                                    GraphSpec graphSpec,
                                    List<String> warnings) {
    }

    private record MatchEvaluation(WorkflowPageAssistantCatalogResponse.MatchStatus status,
                                   String message,
                                   PageActionRegistryEntity catalog) {

        static MatchEvaluation of(WorkflowPageAssistantCatalogResponse.MatchStatus status, String message) {
            return new MatchEvaluation(status, message, null);
        }

        static MatchEvaluation of(WorkflowPageAssistantCatalogResponse.MatchStatus status,
                                  String message,
                                  PageActionRegistryEntity catalog) {
            return new MatchEvaluation(status, message, catalog);
        }
    }
}
