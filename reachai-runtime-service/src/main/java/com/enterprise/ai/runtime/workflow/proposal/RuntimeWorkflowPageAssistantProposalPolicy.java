package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalDraft.ProposalNode;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.bool;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.compactToken;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.firstText;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.slug;
import static com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValues.text;

/** Page-assistant proposal defaults, action selection and authoring constraints. */
final class RuntimeWorkflowPageAssistantProposalPolicy {

    private final RuntimeWorkflowProposalValues values;

    RuntimeWorkflowPageAssistantProposalPolicy(RuntimeWorkflowProposalValues values) {
        this.values = values;
    }

    boolean normalize(RuntimeWorkflowProposalGenerationRequest request, List<ProposalNode> nodes, List<String> warnings) {
        if (!isPageAssistantProposal(request)) return false;
        boolean backfilled = backfillPageAssistantActionsWhenMissing(request, nodes, warnings);
        normalizePageAssistantProposal(request, nodes, warnings);
        return backfilled;
    }

    Map<String, Object> promptTemplate(RuntimeWorkflowProposalGenerationRequest request) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("entryNodeId", "user_input");
        template.put("flowMode", pageAssistantFlowMode(request));
        template.put("allowedActionKeys", pageAssistantAllowedActionKeys(request));
        template.put("recommendedFlow", pageAssistantRecommendedFlow(request));
        template.put("intentClasses", pageAssistantIntentClasses(request));
        template.put("safetyNotes", pageAssistantSafetyNotes(request));
        if (hasPageAction(request, "setFilters")) {
            template.put("extractNode", Map.of(
                    "id", "extract_filters",
                    "outputAlias", "extracted_filters",
                    "outputFormat", "json",
                    "structuredOutput", true));
            template.put("setFiltersArgsPattern", "nodeOutput.extract_filters.<fieldName>");
        }
        template.put("answerTemplate", "正在按你的条件查询页面数据，请稍候…");
        template.put("structuredPresentation", Map.of(
                "interactionType", "PRESENT_OUTPUT",
                "listComponent", "list_card",
                "detailComponent", "output_card",
                "presentationMode", "card_only",
                "dataExpressionPattern", "nodeOutput.<producerNodeId>"));
        return template;
    }

    private boolean backfillPageAssistantActionsWhenMissing(RuntimeWorkflowProposalGenerationRequest request,
                                                            List<ProposalNode> nodes,
                                                            List<String> warnings) {
        if (request == null || request.pageActions() == null || request.pageActions().isEmpty()) {
            return false;
        }
        boolean hasPageAction = nodes.stream().anyMatch(node -> "pageAction".equals(node.canvasKind()));
        if (hasPageAction) {
            return false;
        }
        if ("INTENT_ROUTER".equals(pageAssistantFlowMode(request))) {
            warnings.add("页面助手存在多个互斥意图，模型未生成 INTENT_CLASSIFIER 分支；请按 requirement 中的 intentClasses 手动补充分类节点");
            return false;
        }

        List<ProposalNode> actionNodes = pageAssistantActionNodes(request);
        if (actionNodes.isEmpty()) {
            return false;
        }

        int insertAt = firstNodeIndex(nodes, "answer");
        if (insertAt < 0) {
            insertAt = nodes.size();
        }
        nodes.addAll(insertAt, actionNodes);
        warnings.add("页面助手提案未包含页面动作节点，已按已选 actionKeys 自动补齐执行链路");
        return true;
    }

    private List<ProposalNode> pageAssistantActionNodes(RuntimeWorkflowProposalGenerationRequest request) {
        List<ProposalNode> nodes = new ArrayList<>();
        if (hasPageAction(request, "setFilters")) {
            nodes.add(new ProposalNode(
                    "extract_filters",
                    "LLM",
                    "llm",
                    "提取筛选条件",
                    "从用户问题提取页面筛选条件",
                    new LinkedHashMap<>(Map.of(
                            "configVersion", 2,
                            "source", "AI_PROPOSAL",
                            "outputAlias", "extracted_filters")),
                    List.of(),
                    List.of()));
        }
        Set<String> added = new LinkedHashSet<>();
        List<String> preferred = List.of("setFilters", "search", "readTable", "getPageState", "reset", "openRowAction");
        for (String actionKey : preferred) {
            RuntimeWorkflowProposalResourceView resource = findPageActionResource(request, actionKey);
            if (resource != null) {
                nodes.add(pageAssistantActionNode(request, resource));
                added.add(compactToken(actionResourceKey(resource)));
            }
        }
        for (RuntimeWorkflowProposalResourceView resource : request.pageActions()) {
            if (resource == null) {
                continue;
            }
            String compact = compactToken(actionResourceKey(resource));
            if (added.contains(compact)) {
                continue;
            }
            nodes.add(pageAssistantActionNode(request, resource));
            added.add(compact);
        }
        return nodes;
    }

    private ProposalNode pageAssistantActionNode(RuntimeWorkflowProposalGenerationRequest request, RuntimeWorkflowProposalResourceView resource) {
        Map<String, Object> metadata = values.mutableMap(resource.metadata());
        String actionKey = actionResourceKey(resource);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configVersion", 2);
        config.put("source", "AI_PROPOSAL");
        config.put("projectCode", firstText(text(metadata.get("projectCode")), resource.projectCode(), request.projectCode()));
        config.put("pageKey", text(metadata.get("pageKey")));
        config.put("routePattern", text(metadata.get("routePattern")));
        config.put("actionKey", actionKey);
        config.put("title", firstText(resource.description(), actionKey));
        config.put("confirm", bool(metadata.get("confirmRequired")));
        config.put("args", Map.of());
        config.put("outputAlias", "page_action_result");
        config.put("metadata", Map.of(
                "source", "AI_PROPOSAL",
                "inputSchema", values.mutableMap(metadata.get("inputSchema")),
                "outputSchema", values.mutableMap(metadata.get("outputSchema")),
                "sampleArgs", values.mutableMap(metadata.get("sampleArgs"))));
        return new ProposalNode(
                pageAssistantActionNodeId(actionKey),
                "PAGE_ACTION",
                "pageAction",
                firstText(resource.description(), actionKey),
                firstText(resource.description(), actionKey),
                config,
                List.of(),
                List.of());
    }

    private String actionResourceKey(RuntimeWorkflowProposalResourceView resource) {
        if (resource == null) {
            return "";
        }
        Map<String, Object> metadata = values.mutableMap(resource.metadata());
        return firstText(text(metadata.get("actionKey")), resource.name());
    }

    private String pageAssistantActionNodeId(String actionKey) {
        String compact = compactToken(actionKey);
        if ("setfilters".equals(compact)) {
            return "set_filters";
        }
        if ("readtable".equals(compact)) {
            return "read_table";
        }
        if ("getpagestate".equals(compact)) {
            return "get_page_state";
        }
        if ("openrowaction".equals(compact)) {
            return "open_row_action";
        }
        return slug(actionKey);
    }

    private int firstNodeIndex(List<ProposalNode> nodes, String kind) {
        for (int i = 0; i < nodes.size(); i++) {
            if (kind.equals(nodes.get(i).canvasKind())) {
                return i;
            }
        }
        return -1;
    }

    private void normalizePageAssistantProposal(RuntimeWorkflowProposalGenerationRequest request,
                                           List<ProposalNode> nodes,
                                           List<String> warnings) {
        if (nodes.isEmpty()) {
            return;
        }
        ProposalNode extractNode = findPageAssistantExtractNode(nodes);
        String extractAlias = extractNode == null
                ? "extracted_filters"
                : normalizePageAssistantExtractNode(extractNode, request, warnings);
        String extractNodeId = extractNode == null ? "extract_filters" : extractNode.id();
        for (ProposalNode node : nodes) {
            if (!"pageAction".equals(node.canvasKind())) {
                continue;
            }
            String actionKey = firstText(text(node.config().get("actionKey")), text(node.config().get("action")));
            if (isSetFiltersAction(actionKey)) {
                ensureSetFiltersArgs(node, extractNodeId, extractAlias, request, warnings);
            }
        }
        normalizePageAssistantAnswerNodes(nodes);
    }

    private ProposalNode findPageAssistantExtractNode(List<ProposalNode> nodes) {
        int firstPageActionIndex = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if ("pageAction".equals(nodes.get(i).canvasKind())) {
                firstPageActionIndex = i;
                break;
            }
        }
        if (firstPageActionIndex <= 0) {
            return null;
        }
        for (int i = firstPageActionIndex - 1; i >= 0; i--) {
            ProposalNode candidate = nodes.get(i);
            if ("userInput".equals(candidate.canvasKind())) {
                continue;
            }
            if ("llm".equals(candidate.canvasKind()) || "parameter".equals(candidate.canvasKind())) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizePageAssistantExtractNode(ProposalNode node,
                                                     RuntimeWorkflowProposalGenerationRequest request,
                                                     List<String> warnings) {
        Map<String, Object> config = node.config();
        String alias = firstText(text(config.get("outputAlias")), "extracted_filters");
        config.put("outputAlias", alias);
        List<Map<String, Object>> filterFields = filterFieldDefinitions(request);
        String fieldGuide = buildFilterFieldGuide(filterFields);
        String systemPrompt = buildPageAssistantExtractSystemPrompt(fieldGuide);
        config.put("systemPrompt", systemPrompt);
        if (!filterFields.isEmpty()) {
            config.put("fields", filterFields);
        }
        config.put("userPrompt", "{{ params.question }}");
        config.put("outputFormat", "json");
        config.put("structuredOutput", true);
        config.put("strictJsonSchema", true);
        config.put("contextVariables", List.of("input", "lastOutput", "params"));
        config.put("messages", List.of(
                Map.of("id", "system", "role", "system", "content", systemPrompt, "templateEngine", "mustache", "enabled", true),
                Map.of("id", "user", "role", "user", "content", "{{ params.question }}", "templateEngine", "mustache", "enabled", true)));
        config.put("promptTemplateMode", "messages");
        warnings.add(node.label() + "：已按 setFilters 的 inputSchema 自动补强筛选提取 prompt");
        return alias;
    }

    private void ensureSetFiltersArgs(ProposalNode node,
                                      String extractNodeId,
                                      String extractAlias,
                                      RuntimeWorkflowProposalGenerationRequest request,
                                      List<String> warnings) {
        Map<String, Object> config = node.config();
        Map<String, Object> args = values.mutableMap(config.get("args"));
        if (!args.isEmpty() && args.values().stream().anyMatch(value -> StringUtils.hasText(text(value)))) {
            rewriteSetFiltersArgAliases(args, extractNodeId, extractAlias);
            config.put("args", args);
            return;
        }
        List<String> fields = filterFieldNames(request);
        if (fields.isEmpty()) {
            warnings.add(node.label() + "：setFilters 未提供 inputSchema/sampleArgs，无法自动生成 args");
            return;
        }
        Map<String, Object> wired = new LinkedHashMap<>();
        for (String field : fields) {
            wired.put(field, "nodeOutput." + extractNodeId + "." + field);
        }
        config.put("args", wired);
        warnings.add(node.label() + "：已根据 setFilters inputSchema 自动生成 args 映射");
    }

    private void rewriteSetFiltersArgAliases(Map<String, Object> args, String extractNodeId, String extractAlias) {
        args.replaceAll((key, value) -> {
            String raw = text(value);
            if (!StringUtils.hasText(raw)) {
                return "nodeOutput." + extractNodeId + "." + key;
            }
            String normalized = raw.replace("{{", "").replace("}}", "").trim();
            if (normalized.startsWith("nodeOutput.")) {
                return normalized;
            }
            int dot = normalized.indexOf('.');
            if (dot <= 0) {
                if (normalized.equals(extractAlias) || normalized.equals(extractNodeId)) {
                    return "nodeOutput." + extractNodeId + "." + key;
                }
                return raw.contains("{{") ? "nodeOutput." + extractNodeId + "." + key : raw;
            }
            String suffix = normalized.substring(dot + 1);
            if (!StringUtils.hasText(suffix)) {
                return "nodeOutput." + extractNodeId + "." + key;
            }
            return "nodeOutput." + extractNodeId + "." + suffix;
        });
    }

    private void normalizePageAssistantAnswerNodes(List<ProposalNode> nodes) {
        boolean hasPageAction = nodes.stream().anyMatch(node -> "pageAction".equals(node.canvasKind()));
        if (!hasPageAction) {
            return;
        }
        for (ProposalNode node : nodes) {
            if (!"answer".equals(node.canvasKind())) {
                continue;
            }
            Map<String, Object> config = node.config();
            String template = text(config.get("template"));
            if (!StringUtils.hasText(template) || template.contains("lastOutput")) {
                config.put("template", "正在按你的条件查询页面数据，请稍候…");
            }
        }
    }

    private List<String> filterFieldNames(RuntimeWorkflowProposalGenerationRequest request) {
        List<Map<String, Object>> definitions = filterFieldDefinitions(request);
        if (!definitions.isEmpty()) {
            return definitions.stream()
                    .map(field -> text(field.get("name")))
                    .filter(StringUtils::hasText)
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private List<Map<String, Object>> filterFieldDefinitions(RuntimeWorkflowProposalGenerationRequest request) {
        RuntimeWorkflowProposalResourceView setFilters = findPageActionResource(request, "setFilters");
        if (setFilters == null) {
            return List.of();
        }
        Map<String, Object> metadata = values.mutableMap(setFilters.metadata());
        Map<String, Object> inputSchema = values.mutableMap(metadata.get("inputSchema"));
        Map<String, Object> properties = values.mutableMap(inputSchema.get("properties"));
        Map<String, Object> sampleArgs = values.mutableMap(metadata.get("sampleArgs"));
        Set<String> names = new java.util.TreeSet<>();
        if (!properties.isEmpty()) {
            names.addAll(properties.keySet());
        }
        names.addAll(sampleArgs.keySet());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String name : names) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> property = values.mutableMap(properties.get(name));
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name);
            field.put("key", name);
            field.put("type", firstText(text(property.get("type")), typeName(sampleArgs.get(name)), "string"));
            putIfText(field, "label", firstText(text(property.get("label")), text(property.get("title"))));
            putIfText(field, "title", text(property.get("title")));
            putIfText(field, "description", text(property.get("description")));
            Object aliases = firstPresent(property, "aliases", "synonyms");
            if (aliases != null) {
                field.put("aliases", aliases);
            }
            fields.add(field);
        }
        return fields;
    }

    private RuntimeWorkflowProposalResourceView findPageActionResource(RuntimeWorkflowProposalGenerationRequest request, String actionKey) {
        if (request == null || request.pageActions() == null || !StringUtils.hasText(actionKey)) {
            return null;
        }
        String targetCompact = compactToken(actionKey);
        for (RuntimeWorkflowProposalResourceView resource : request.pageActions()) {
            if (resource == null) {
                continue;
            }
            if (actionKey.equalsIgnoreCase(resource.name())) {
                return resource;
            }
            Map<String, Object> metadata = values.mutableMap(resource.metadata());
            String metaActionKey = text(metadata.get("actionKey"));
            if (actionKey.equalsIgnoreCase(metaActionKey)) {
                return resource;
            }
            if (StringUtils.hasText(targetCompact)
                    && (targetCompact.equals(compactToken(resource.name()))
                    || targetCompact.equals(compactToken(metaActionKey)))) {
                return resource;
            }
        }
        return null;
    }

    private boolean isSetFiltersAction(String actionKey) {
        if (!StringUtils.hasText(actionKey)) {
            return false;
        }
        String compact = compactToken(actionKey);
        return "setfilters".equals(compact)
                || actionKey.toLowerCase(Locale.ROOT).contains("setfilter")
                || actionKey.toLowerCase(Locale.ROOT).contains("set_filter");
    }

    private String buildFilterFieldGuide(List<Map<String, Object>> fields) {
        if (fields.isEmpty()) {
            return "按页面 setFilters 动作可接受的筛选字段输出 JSON；字段名与页面动作 inputSchema 保持一致。";
        }
        StringBuilder guide = new StringBuilder("仅输出以下字段组成的 JSON 对象（键名必须完全一致）。")
                .append("字段语义只能来自当前 setFilters inputSchema 的 title/label/description/aliases，不要套用其他页面的字段名：");
        for (Map<String, Object> field : fields) {
            String name = text(field.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            guide.append("\n- ").append(name);
            String label = firstText(text(field.get("label")), text(field.get("title")));
            if (StringUtils.hasText(label)) {
                guide.append("：").append(label);
            }
            String description = text(field.get("description"));
            if (StringUtils.hasText(description)) {
                guide.append("；").append(description);
            }
            String aliases = aliasText(field.get("aliases"));
            if (StringUtils.hasText(aliases)) {
                guide.append("；别名：").append(aliases);
            }
        }
        guide.append("。用户未提及的字段不要编造，可省略该键。");
        return guide.toString();
    }

    private String buildPageAssistantExtractSystemPrompt(String fieldGuide) {
        return "你是页面助手工作流中的筛选条件提取节点。"
                + fieldGuide
                + " 只能根据当前页面字段定义做同义理解：如果某个页面把“负责人”声明为 owner，就输出 owner；如果声明为 principalUserName，就输出 principalUserName。"
                + " 只输出 JSON 对象，不要输出解释、Markdown 或代码块。";
    }

    boolean isPageAssistantProposal(RuntimeWorkflowProposalGenerationRequest request) {
        if (request == null) {
            return false;
        }
        if ("PAGE_ASSISTANT".equalsIgnoreCase(text(request.workflowKind()))) {
            return true;
        }
        return request.pageActions() != null && !request.pageActions().isEmpty();
    }

    private List<String> pageAssistantAllowedActionKeys(RuntimeWorkflowProposalGenerationRequest request) {
        if (request == null || request.pageActions() == null) {
            return List.of();
        }
        return request.pageActions().stream()
                .filter(Objects::nonNull)
                .map(resource -> {
                    Map<String, Object> metadata = values.mutableMap(resource.metadata());
                    return firstText(text(metadata.get("actionKey")), resource.name());
                })
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> pageAssistantRecommendedFlow(RuntimeWorkflowProposalGenerationRequest request) {
        if ("INTENT_ROUTER".equals(pageAssistantFlowMode(request))) {
            List<String> flow = new ArrayList<>();
            flow.add("user_input");
            flow.add("intent_router");
            for (Map<String, Object> intentClass : pageAssistantIntentClasses(request)) {
                flow.add("route:" + text(intentClass.get("id")));
            }
            flow.add("route:else");
            flow.add("answer_else");
            return flow;
        }
        List<String> flow = new ArrayList<>();
        flow.add("user_input");
        if (hasPageAction(request, "setFilters")) {
            flow.add("extract_filters");
            flow.add("set_filters");
        }
        List<String> preferred = List.of("search", "readTable", "getPageState", "reset", "openRowAction");
        Set<String> added = new LinkedHashSet<>();
        for (String actionKey : preferred) {
            if (hasPageAction(request, actionKey)) {
                flow.add(slug(actionKey));
                added.add(compactToken(actionKey));
            }
        }
        for (String actionKey : pageAssistantAllowedActionKeys(request)) {
            String compact = compactToken(actionKey);
            if (!added.contains(compact) && !isSetFiltersAction(actionKey)) {
                flow.add(slug(actionKey));
            }
        }
        flow.add("answer");
        return flow;
    }

    private String pageAssistantFlowMode(RuntimeWorkflowProposalGenerationRequest request) {
        List<String> actionKeys = pageAssistantAllowedActionKeys(request);
        if (actionKeys.size() <= 1) {
            return "LINEAR_QUERY";
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String actionKey : actionKeys) {
            categories.add(pageAssistantActionCategory(request, actionKey));
        }
        if (categories.stream().allMatch("query_chain"::equals)) {
            return "LINEAR_QUERY";
        }
        boolean hasQueryChain = categories.contains("query_chain");
        boolean hasReset = categories.contains("reset");
        boolean hasPageState = categories.contains("page_state");
        boolean hasOperational = categories.contains("operational");
        boolean hasOther = categories.contains("other");
        if (hasQueryChain && (hasReset || hasPageState || hasOperational || hasOther)) {
            return "INTENT_ROUTER";
        }
        int exclusiveGroups = 0;
        if (hasReset) exclusiveGroups++;
        if (hasPageState) exclusiveGroups++;
        if (hasOperational) exclusiveGroups++;
        if (hasOther) exclusiveGroups++;
        if (exclusiveGroups >= 2) {
            return "INTENT_ROUTER";
        }
        if (exclusiveGroups >= 1 && hasQueryChain) {
            return "INTENT_ROUTER";
        }
        return "INTENT_ROUTER";
    }

    private String pageAssistantActionCategory(RuntimeWorkflowProposalGenerationRequest request, String actionKey) {
        String compact = compactToken(actionKey);
        if (isSetFiltersAction(actionKey)) {
            return "query_chain";
        }
        if ("search".equals(compact) || actionKey.toLowerCase(Locale.ROOT).contains("search")
                || actionKey.toLowerCase(Locale.ROOT).contains("query")) {
            return "query_chain";
        }
        if ("readtable".equals(compact) || compact.contains("readtable")) {
            return "query_chain";
        }
        if ("reset".equals(compact) || compact.contains("reset")) {
            return "reset";
        }
        if ("getpagestate".equals(compact) || compact.contains("pagestate")) {
            return "page_state";
        }
        RuntimeWorkflowProposalResourceView resource = findPageActionResource(request, actionKey);
        if (resource != null && bool(values.mutableMap(resource.metadata()).get("confirmRequired"))) {
            return "operational";
        }
        if (compact.contains("openrow") || compact.contains("delete") || compact.contains("submit")
                || compact.contains("approve") || compact.contains("export")) {
            return "operational";
        }
        return "other";
    }

    private List<Map<String, Object>> pageAssistantIntentClasses(RuntimeWorkflowProposalGenerationRequest request) {
        if (!"INTENT_ROUTER".equals(pageAssistantFlowMode(request))) {
            return List.of();
        }
        List<Map<String, Object>> classes = new ArrayList<>();
        List<String> queryActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "query_chain".equals(pageAssistantActionCategory(request, actionKey)))
                .toList();
        if (!queryActionKeys.isEmpty()) {
            classes.add(pageAssistantIntentClass(
                    "query_intent",
                    "查询数据",
                    "用户想按条件查询、搜索或读取表格结果",
                    List.of("查询", "搜索", "筛选", "查找", "表格", "列表", "查一下"),
                    queryActionKeys,
                    false));
        }
        List<String> resetActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "reset".equals(pageAssistantActionCategory(request, actionKey)))
                .toList();
        if (!resetActionKeys.isEmpty()) {
            classes.add(pageAssistantIntentClass(
                    "reset_intent",
                    resetActionKeys.size() == 1 ? pageAssistantActionLabel(request, resetActionKeys.get(0), "重置筛选") : "重置筛选",
                    "用户想清空或重置页面筛选条件",
                    List.of("重置", "清空", "恢复默认", "清除筛选"),
                    resetActionKeys,
                    false));
        }
        List<String> pageStateActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "page_state".equals(pageAssistantActionCategory(request, actionKey)))
                .toList();
        if (!pageStateActionKeys.isEmpty()) {
            classes.add(pageAssistantIntentClass(
                    "page_state_intent",
                    pageStateActionKeys.size() == 1
                            ? pageAssistantActionLabel(request, pageStateActionKeys.get(0), "读取页面状态")
                            : "读取页面状态",
                    "用户想查看当前筛选、分页或表格状态",
                    List.of("当前状态", "页面状态", "现在筛选", "当前条件", "分页"),
                    pageStateActionKeys,
                    false));
        }
        List<String> openRowActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "operational".equals(pageAssistantActionCategory(request, actionKey)))
                .filter(actionKey -> compactToken(actionKey).contains("openrow"))
                .toList();
        if (!openRowActionKeys.isEmpty()) {
            classes.add(pageAssistantIntentClass(
                    "row_action_intent",
                    openRowActionKeys.size() == 1
                            ? pageAssistantActionLabel(request, openRowActionKeys.get(0), "行内操作")
                            : "行内操作",
                    "用户想执行表格行内操作",
                    List.of("打开", "行操作", "周期", "详情", "操作"),
                    openRowActionKeys,
                    openRowActionKeys.stream().anyMatch(actionKey -> pageAssistantActionConfirmRequired(request, actionKey))));
        }
        for (String actionKey : pageAssistantAllowedActionKeys(request)) {
            String category = pageAssistantActionCategory(request, actionKey);
            if (!"operational".equals(category) || compactToken(actionKey).contains("openrow")) {
                continue;
            }
            String label = pageAssistantActionLabel(request, actionKey, actionKey);
            classes.add(pageAssistantIntentClass(
                    slug(actionKey) + "_intent",
                    label,
                    "用户想执行页面操作：" + label,
                    List.of(label, "操作", "执行"),
                    List.of(actionKey),
                    pageAssistantActionConfirmRequired(request, actionKey)));
        }
        List<String> otherActionKeys = pageAssistantAllowedActionKeys(request).stream()
                .filter(actionKey -> "other".equals(pageAssistantActionCategory(request, actionKey)))
                .toList();
        if (otherActionKeys.size() == 1) {
            String actionKey = otherActionKeys.get(0);
            String label = pageAssistantActionLabel(request, actionKey, actionKey);
            classes.add(pageAssistantIntentClass(
                    slug(actionKey) + "_intent",
                    label,
                    "用户想执行页面操作：" + label,
                    List.of(label, "操作", "执行"),
                    List.of(actionKey),
                    false));
        } else if (otherActionKeys.size() > 1) {
            classes.add(pageAssistantIntentClass(
                    "other_intent",
                    "其他页面操作",
                    "用户想执行其他已注册的页面动作",
                    List.of("操作", "执行", "处理"),
                    otherActionKeys,
                    false));
        }
        return classes;
    }

    private String pageAssistantActionLabel(RuntimeWorkflowProposalGenerationRequest request, String actionKey, String fallback) {
        RuntimeWorkflowProposalResourceView resource = findPageActionResource(request, actionKey);
        return resource == null ? fallback : firstText(resource.description(), actionKey, fallback);
    }

    private boolean pageAssistantActionConfirmRequired(RuntimeWorkflowProposalGenerationRequest request, String actionKey) {
        RuntimeWorkflowProposalResourceView resource = findPageActionResource(request, actionKey);
        return resource != null && bool(values.mutableMap(resource.metadata()).get("confirmRequired"));
    }

    private Map<String, Object> pageAssistantIntentClass(String id,
                                                         String label,
                                                         String description,
                                                         List<String> keywords,
                                                         List<String> actionKeys,
                                                         boolean requiresConfirm) {
        Map<String, Object> intentClass = new LinkedHashMap<>();
        intentClass.put("id", id);
        intentClass.put("label", label);
        intentClass.put("description", description);
        intentClass.put("keywords", keywords);
        intentClass.put("actionKeys", actionKeys);
        intentClass.put("requiresConfirm", requiresConfirm);
        return intentClass;
    }

    private List<String> pageAssistantSafetyNotes(RuntimeWorkflowProposalGenerationRequest request) {
        if (request == null || request.pageActions() == null) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (RuntimeWorkflowProposalResourceView resource : request.pageActions()) {
            if (resource == null) {
                continue;
            }
            Map<String, Object> metadata = values.mutableMap(resource.metadata());
            String actionKey = actionResourceKey(resource);
            if (bool(metadata.get("confirmRequired"))) {
                notes.add(actionKey + " 为需确认动作：route 分支生成 PAGE_ACTION(" + actionKey
                        + ") 且 config.confirm=true；Page Bridge 在动作执行前确认，用户拒绝则不执行；"
                        + "ANSWER 仅报告成功/拒绝/失败，不要生成 INTERACTION，也不要在动作后再要求确认。");
            }
        }
        return notes;
    }

    private boolean hasPageAction(RuntimeWorkflowProposalGenerationRequest request, String actionKey) {
        return findPageActionResource(request, actionKey) != null;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String typeName(Object value) {
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return "";
    }

    private String aliasText(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(RuntimeWorkflowProposalValues::text)
                    .filter(StringUtils::hasText)
                    .toList()
                    .stream()
                    .reduce((left, right) -> left + "/" + right)
                    .orElse("");
        }
        return text(value);
    }
}
