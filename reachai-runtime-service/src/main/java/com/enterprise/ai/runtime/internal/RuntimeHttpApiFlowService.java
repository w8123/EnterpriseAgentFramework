package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.WorkflowSemanticValues;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * HTTP API → single-call Workflow. Employees and the embedded assistant can only be given business
 * methods and published Workflows; an accepted HTTP API reaches them by being wrapped as
 * {@code user input → API → result}. The wrapper is ordinary Studio content and keeps every Workflow
 * guarantee (owner contract pin at publish, project connection and credentials, write risk floor), and
 * it has no model node, so calling it costs no tokens.
 */
@Service
@RequiredArgsConstructor
public class RuntimeHttpApiFlowService {

    static final String SOURCE = "HTTP_API_FLOW";
    private static final int MAX_BATCH = 300;
    private static final String VERSION = "v1.0.0";
    private static final Set<String> INPUT_TYPES = Set.of("string", "integer", "number", "boolean", "array", "object");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RuntimeCapabilityCatalogClient catalog;
    private final RuntimeWorkflowManagementService workflows;
    private final RuntimeWorkflowAiCodingService workflowAiCodingService;

    /** One result per requested API; a failing API never stops the rest of the batch. */
    public List<FlowResult> generate(BatchRequest request) {
        if (request == null || request.projectId() == null || !StringUtils.hasText(request.projectCode())) {
            throw new IllegalArgumentException("projectId 和 projectCode 必填");
        }
        List<ApiItem> apis = request.apis() == null ? List.of() : request.apis();
        if (apis.isEmpty()) throw new IllegalArgumentException("请至少选择一个 API");
        if (apis.size() > MAX_BATCH) throw new IllegalArgumentException("一次最多处理 " + MAX_BATCH + " 个 API");
        boolean publish = Boolean.TRUE.equals(request.publish());
        String publishedBy = StringUtils.hasText(request.publishedBy()) ? request.publishedBy().trim() : "ReachAI";
        List<FlowResult> results = new ArrayList<>();
        for (ApiItem api : apis) {
            if (api == null || api.apiId() == null || api.apiId() <= 0) continue;
            try {
                results.add(one(request.projectId(), request.projectCode().trim(), api, publish, publishedBy));
            } catch (RuntimeException failed) {
                results.add(new FlowResult(api.apiId(), null, null, null, null, false,
                        StringUtils.hasText(failed.getMessage()) ? failed.getMessage() : "生成失败"));
            }
        }
        return results;
    }

    private FlowResult one(Long projectId, String projectCode, ApiItem api, boolean publish, String publishedBy) {
        HttpApiConsoleContracts.ExecutionContext owner;
        try {
            owner = catalog.getHttpApiExecutionContext(api.apiId(), projectCode);
        } catch (RuntimeException unavailable) {
            throw new IllegalArgumentException("API 不存在、不属于这个项目，或目录暂时不可用");
        }
        if (owner == null || !projectCode.equals(owner.projectCode()) || !projectId.equals(owner.projectId())
                || !api.apiId().equals(owner.apiId())) {
            throw new IllegalArgumentException("API 不属于这个项目");
        }
        if (!owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus())
                || !StringUtils.hasText(owner.acceptedContractHash())
                || !owner.acceptedContractHash().equals(owner.candidateContractHash())
                || owner.acceptedContract() == null || owner.acceptedContract().isNull()) {
            throw new IllegalArgumentException(StringUtils.hasText(owner.sourceReason())
                    ? owner.sourceReason() : "API 还没有接纳，或来源契约已变化，请先在 API 详情里接纳");
        }
        if (!owner.consoleCallSupported()) {
            throw new IllegalArgumentException(StringUtils.hasText(owner.consoleUnsupportedReason())
                    ? owner.consoleUnsupportedReason() : "这个 API 的参数形态暂不支持在流程里调用");
        }
        String method = owner.httpMethod() == null ? "" : owner.httpMethod().toUpperCase(Locale.ROOT);
        String title = StringUtils.hasText(api.title()) ? api.title().trim() : method + " " + owner.routeTemplate();
        String keySlug = keySlug(projectCode, api.apiId());
        RuntimeWorkflowDefinitionView workflow = workflows.findByKeySlug(keySlug).orElse(null);
        String legacySlug = legacyKeySlug(projectCode, api.apiId());
        if (workflow == null && !legacySlug.equals(keySlug)) {
            RuntimeWorkflowDefinitionView legacy = workflows.findByKeySlug(legacySlug).orElse(null);
            if (legacy != null && matchesWorkflow(legacy, owner)) workflow = legacy;
        }
        boolean created = false;
        if (workflow == null) {
            var context = workflowAiCodingService.createWorkflow(new RuntimeWorkflowAiCodingService.CreateRequest(
                    title.length() > 80 ? title.substring(0, 80) : title,
                    keySlug,
                    projectId,
                    projectCode,
                    description(title, method, owner.routeTemplate(), api.description(), owner.acceptedContract()),
                    WorkflowSemanticValues.KIND_GENERAL,
                    WorkflowSemanticValues.ENGINE_GRAPH_SPEC,
                    null,
                    graphSpec(owner, title),
                    null,
                    Map.of("source", SOURCE, "httpApiAssetId", api.apiId(), "sourceRef", owner.qualifiedName()),
                    List.of(),
                    "把 HTTP API「" + title + "」包装成流程，供员工和助手调用"));
            workflow = workflows.findById(context.workflow().id())
                    .orElseThrow(() -> new IllegalStateException("流程创建失败"));
            created = true;
        } else if (!matchesWorkflow(workflow, owner)) {
            throw new IllegalArgumentException("同名流程的来源或草稿与所选 API 不一致，无法复用");
        }
        if ("ACTIVE".equalsIgnoreCase(workflow.getStatus())) verifyActiveVersion(workflow, owner);
        if (publish && !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
            RuntimeWorkflowReleaseValidationResult validation = workflows.validateRelease(workflow.getId());
            if (!validation.valid()) {
                String reason = validation.errors().isEmpty() ? "UNKNOWN" : validation.errors().get(0).message();
                return new FlowResult(api.apiId(), workflow.getId(), workflow.getKeySlug(), workflow.getName(),
                        workflow.getStatus(), created, "流程草稿已生成，但发布校验未通过: " + reason);
            }
            int published = workflows.listVersions(workflow.getId()).size();
            workflows.publish(workflow.getId(), published == 0 ? VERSION : "v1.0." + published, 100,
                    "HTTP API「" + title + "」发布为流程", publishedBy, workflow.getUpdatedAt().toString());
            workflow = workflows.findById(workflow.getId()).orElse(workflow);
            verifyActiveVersion(workflow, owner);
        }
        return new FlowResult(api.apiId(), workflow.getId(), workflow.getKeySlug(), workflow.getName(),
                workflow.getStatus(), created, null);
    }

    GraphSpec graphSpec(HttpApiConsoleContracts.ExecutionContext owner, String title) {
        JsonNode contract = owner.acceptedContract();
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        Map<String, Object> inputMapping = new LinkedHashMap<>();
        for (JsonNode parameter : contract.path("parameters")) {
            String location = parameter.path("location").asText("").toUpperCase(Locale.ROOT);
            if (!"PATH".equals(location) && !"QUERY".equals(location)) continue;
            boolean path = "PATH".equals(location);
            addInput(fields, properties, required, inputMapping,
                    (path ? "pathParams." : "queryParams.") + parameter.path("name").asText(""),
                    parameter.path("name").asText(""), parameter.path("schema"),
                    path || parameter.path("required").asBoolean(false));
        }
        JsonNode body = contract.path("requestBody").path("schema");
        boolean flattenBody = body.path("properties").isObject()
                && contract.path("requestBody").path("required").asBoolean(false)
                && !body.path("nullable").asBoolean(false) && !body.has("minProperties") && !body.has("maxProperties")
                && !body.has("enum") && !body.has("const")
                && (!body.has("additionalProperties") || body.path("additionalProperties").isBoolean()
                    && !body.path("additionalProperties").asBoolean());
        if (flattenBody) {
            Set<String> bodyRequired = new java.util.LinkedHashSet<>();
            body.path("required").forEach(name -> bodyRequired.add(name.asText()));
            body.path("properties").fields().forEachRemaining(entry -> addInput(fields, properties, required,
                    inputMapping, "body." + entry.getKey(), entry.getKey(), entry.getValue(),
                    bodyRequired.contains(entry.getKey())));
        } else if (!body.isMissingNode() && !body.isNull()) {
            addInput(fields, properties, required, inputMapping, "body", "body", body,
                    contract.path("requestBody").path("required").asBoolean(false));
        }
        Map<String, Object> userInput = new LinkedHashMap<>();
        userInput.put("fields", fields);
        userInput.put("outputAlias", "params");

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("ref", owner.qualifiedName());
        tool.put("qualifiedName", owner.qualifiedName());
        tool.put("projectCode", owner.projectCode());
        tool.put("httpApiAssetId", owner.apiId());
        tool.put("inputMapping", inputMapping);
        tool.put("argumentSource", "inputMapping");
        tool.put("outputAlias", "api");
        Map<String, Object> assetReference = new LinkedHashMap<>();
        assetReference.put("kind", "TOOL");
        assetReference.put("assetType", "HTTP_API");
        assetReference.put("assetId", owner.apiId());
        assetReference.put("name", owner.qualifiedName());
        assetReference.put("qualifiedName", owner.qualifiedName());
        assetReference.put("projectCode", owner.projectCode());
        tool.put("assetReference", assetReference);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("template", "{{ nodeOutput.api }}");

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) inputSchema.put("required", required);
        inputSchema.put("additionalProperties", false);

        return GraphSpec.builder()
                .inputSchema(inputSchema)
                .entryNodeId("user_input")
                .exitNodeIds(List.of("answer"))
                .node(GraphSpec.Node.builder().id("user_input").type("USER_INPUT").name("调用参数")
                        .config(userInput).build())
                .node(GraphSpec.Node.builder().id("api").type("TOOL").name(title)
                        .ref(GraphSpec.CapabilityRef.builder().kind("TOOL")
                                .name(owner.qualifiedName()).qualifiedName(owner.qualifiedName())
                                .projectCode(owner.projectCode()).build())
                        .config(tool).build())
                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").name("返回结果")
                        .config(answer).build())
                .edge(GraphSpec.Edge.builder().id("e_input_api").from("user_input").to("api")
                        .condition("always").build())
                .edge(GraphSpec.Edge.builder().id("e_api_answer").from("api").to("answer")
                        .condition("always").build())
                .build();
    }

    private static void addInput(List<Map<String, Object>> fields, Map<String, Object> properties,
                                 List<String> required, Map<String, Object> inputMapping,
                                 String target, String rawName, JsonNode schema, boolean isRequired) {
        if (!StringUtils.hasText(rawName)) throw new IllegalArgumentException("API 参数缺少名称");
        String type = schema == null ? "" : schema.path("type").asText("").toLowerCase(Locale.ROOT);
        if (!INPUT_TYPES.contains(type)) throw new IllegalArgumentException("API 参数类型暂不支持: " + rawName);
        String name = rawName.replaceAll("[^A-Za-z0-9_]", "_");
        if (properties.containsKey(name)) name = target.replaceAll("[^A-Za-z0-9_]", "_");
        if (properties.containsKey(name)) throw new IllegalArgumentException("API 参数名称冲突: " + rawName);
        String declared = schema == null ? "" : schema.path("description").asText("");
        String description = (StringUtils.hasText(declared) ? declared : rawName) + optionalHint(rawName, type, isRequired);
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("key", name);
        field.put("type", type);
        field.put("required", isRequired);
        field.put("description", description);
        field.put("source", "input." + name);
        fields.add(field);
        @SuppressWarnings("unchecked")
        Map<String, Object> property = schema != null && schema.isObject()
                ? JSON.convertValue(schema, LinkedHashMap.class) : new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        properties.put(name, property);
        if (isRequired) required.add(name);
        inputMapping.put(target, "params." + name);
    }

    /**
     * Paging parameters get a usable default: many systems reject a list call without them, or with a
     * page size below their minimum, even when the contract marks them optional.
     */
    static String optionalHint(String rawName, String type, boolean isRequired) {
        String lower = rawName.toLowerCase(Locale.ROOT);
        boolean number = "integer".equals(type) || "number".equals(type);
        if (number && lower.matches("page|pageno|pagenum|pagenumber|pageindex|current|currentpage")) {
            return "；用户没有提到就填 1";
        }
        if (number && lower.matches("pagesize|size|limit|rows|perpage|per_page|page_size")) {
            return "；用户没有提到就填 10";
        }
        return isRequired ? "" : "；用户没有提到就不要填";
    }

    static String keySlug(String projectCode, long apiId) {
        String slug = ("api-" + projectCode).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String suffix = "-" + apiId;
        if (slug.length() + suffix.length() > 64) slug = slug.substring(0, 64 - suffix.length()).replaceAll("-+$", "");
        return slug + suffix;
    }

    private static String legacyKeySlug(String projectCode, long apiId) {
        String slug = ("api-" + projectCode + "-" + apiId).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.length() > 64 ? slug.substring(0, 64) : slug;
    }

    private boolean matchesWorkflow(RuntimeWorkflowDefinitionView workflow, HttpApiConsoleContracts.ExecutionContext owner) {
        if (!Objects.equals(workflow.getProjectId(), owner.projectId())
                || !Objects.equals(workflow.getProjectCode(), owner.projectCode())) return false;
        try {
            JsonNode extra = JSON.readTree(workflow.getExtraJson());
            return extra != null && SOURCE.equals(extra.path("source").asText())
                    && extra.path("httpApiAssetId").asLong(-1) == owner.apiId()
                    && owner.qualifiedName().equals(extra.path("sourceRef").asText())
                    && matchesGraph(workflow.getGraphSpecJson(), owner, false);
        } catch (Exception invalid) {
            return false;
        }
    }

    private void verifyActiveVersion(RuntimeWorkflowDefinitionView workflow, HttpApiConsoleContracts.ExecutionContext owner) {
        var active = workflows.listVersions(workflow.getId()).stream()
                .filter(version -> "ACTIVE".equalsIgnoreCase(version.getStatus())).toList();
        if (active.size() != 1) throw new IllegalArgumentException("流程的活动版本无法确认，请检查后重试");
        var version = active.get(0);
        String graph = version.getGraphSpecSnapshotJson();
        if (!StringUtils.hasText(graph)) {
            try { graph = JSON.readTree(version.getSnapshotJson()).path("graphSpec").asText(); }
            catch (Exception invalid) { graph = null; }
        }
        if (!matchesGraph(graph, owner, true)) {
            throw new IllegalArgumentException("流程的活动版本与所选 API 不一致，请检查后重新发布");
        }
    }

    private static boolean matchesGraph(String graphJson, HttpApiConsoleContracts.ExecutionContext owner, boolean pinned) {
        try {
            JsonNode nodes = JSON.readTree(graphJson).path("nodes");
            if (!nodes.isArray()) return false;
            int tools = 0;
            for (JsonNode node : nodes) {
                if (!"TOOL".equalsIgnoreCase(node.path("type").asText())) continue;
                tools++;
                JsonNode config = node.path("config");
                JsonNode ref = node.path("ref");
                if (config.path("httpApiAssetId").asLong(-1) != owner.apiId()
                        || !owner.qualifiedName().equals(ref.path("qualifiedName").asText())
                        || !owner.qualifiedName().equals(config.path("qualifiedName").asText())) return false;
                if (pinned && (!"HTTP_API".equals(ref.path("assetType").asText())
                        || ref.path("assetId").asLong(-1) != owner.apiId()
                        || ref.path("definitionId").asLong(-1) <= 0)) return false;
            }
            return tools == 1;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static String description(String title, String method, String route, String extra, JsonNode contract) {
        String base = title + "（" + method + " " + route + "）。"
                + (com.enterprise.ai.common.capability.HttpApiRequestPolicy.requiresWriteConfirmation(
                        contract.path("sideEffect").asText()) ? "可能修改业务数据，员工调用按业务系统的审批设置处理。" : "直接返回接口结果。");
        String text = StringUtils.hasText(extra) ? base + extra.trim() : base;
        return text.length() > 480 ? text.substring(0, 480) : text;
    }

    public record ApiItem(Long apiId, String title, String description) { }

    public record BatchRequest(Long projectId, String projectCode, List<ApiItem> apis, Boolean publish,
                               String publishedBy) { }

    public record FlowResult(Long apiId, String workflowId, String workflowKeySlug, String workflowName,
                             String workflowStatus, boolean created, String error) { }
}
