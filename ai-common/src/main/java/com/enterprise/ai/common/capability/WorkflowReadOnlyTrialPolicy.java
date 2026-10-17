package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared policy for the Studio "read-only trial of the whole saved draft" (D28).
 *
 * <p>Safety model. A trial runs the saved working copy for real, with the attested project test identity, only
 * when every external side-effect node in the graph is provably read-only:</p>
 * <ul>
 *   <li>Every TOOL node is classified from the saved GraphSpec into an HTTP API or a business-method target.
 *       Control and Runtime each re-read the owner (accepted, enabled, READY, contract-current, declared
 *       READ_ONLY, credential available) and the per-target Tool ACL before anything runs.</li>
 *   <li>Every other node must be a pure/model/read node ({@link #PURE_NODE_TYPES}); page actions, blocking
 *       interactions, unknown node types and raw HTTP_REQUEST nodes that are not credential-free GET/HEAD are
 *       refused with a node-named Chinese reason before any dispatch.</li>
 *   <li>The previous single-node trial pinned the exact browser-typed {@code expectedInput}. A downstream node's
 *       input is produced by LLM/PARAMETER_EXTRACT/variable nodes, so it cannot be precomputed. It is replaced by:
 *       (1) a pinned node set — only TOOL nodes present in the hashed saved graph, with the owner facts approved by
 *       Control, may dispatch, each to exactly its own qualified name; (2) the saved revision and graph SHA-256 are
 *       rechecked immediately before every dispatch; (3) a bounded number of dispatches per node and per trial;
 *       (4) the signed Studio constraint makes Capability validate the runtime input against the method's declared
 *       input contract (the same validator as the Console trial) and refuse anything but READ_ONLY. The remaining
 *       risk — model-chosen values for a read-only call under the project test identity — is the same authority the
 *       Console trial already grants a person holding WORKFLOW_DEBUG + CAPABILITY_INVOKE and the per-target ACL.</li>
 * </ul>
 */
public final class WorkflowReadOnlyTrialPolicy {
    public static final int VERSION = 2;
    public static final String HTTP_API = "HTTP_API";
    public static final String BUSINESS_METHOD = "BUSINESS_METHOD";
    public static final String STUDIO_CONSTRAINT = "studioReadOnlyTrial";
    /** External targets a single trial may pin. */
    public static final int MAX_TARGETS = 16;
    /** Read-only re-dispatch bound per pinned node (loops, retries). */
    public static final int MAX_DISPATCHES_PER_NODE = 20;
    /** Read-only dispatch bound across the whole trial. */
    public static final int MAX_DISPATCHES = 50;
    /** Whole-draft trials include model nodes; the signed command is valid for at most this long. */
    public static final long MAX_DURATION_MS = 120_000L;
    public static final int MAX_INPUT_FIELDS = 32;
    public static final int MAX_MESSAGE_LENGTH = 4_000;

    /** Nodes that never reach an external side effect on their own (model, retrieval, flow and variable nodes). */
    public static final Set<String> PURE_NODE_TYPES = Set.of("USER_INPUT", "LLM", "INTENT_CLASSIFIER",
            "PARAMETER_EXTRACT", "IF_ELSE", "VARIABLE_ASSIGN", "TEMPLATE", "VARIABLE_AGGREGATOR", "ANSWER",
            "KNOWLEDGE_RETRIEVAL", "LOOP");
    private static final Map<String, String> TYPE_LABELS = Map.ofEntries(
            Map.entry("PAGE_ACTION", "页面动作"), Map.entry("INTERACTION", "人工交互"),
            Map.entry("HUMAN_APPROVAL", "人工审批"), Map.entry("MCP_CALL", "MCP 调用"),
            Map.entry("KNOWLEDGE_WRITE", "知识写入"), Map.entry("HTTP_REQUEST", "HTTP 请求"),
            Map.entry("TOOL", "业务方法/API"), Map.entry("CODE", "代码"), Map.entry("DOCUMENT_EXTRACT", "文档提取"));

    private WorkflowReadOnlyTrialPolicy() { }

    /** One external node of the saved draft that the trial will really call. */
    public record Target(String nodeId, String nodeName, long apiId, String qualifiedName, String assetType) {
        public Target {
            nodeName = nodeName == null || nodeName.isBlank() ? nodeId : nodeName;
        }
    }

    /** A node that makes the whole draft ineligible, with a user-facing Chinese reason. */
    public record BlockedNode(String nodeId, String nodeName, String nodeType, String reason) { }

    public record Plan(List<Target> targets) {
        public Plan { targets = List.copyOf(targets); }
    }

    /** The draft contains nodes that may write, wait for a person or cannot be classified. Nothing was dispatched. */
    public static final class GraphRejected extends IllegalArgumentException {
        private final transient List<BlockedNode> blocked;
        public GraphRejected(List<BlockedNode> blocked) {
            super("WORKFLOW_TRIAL_GRAPH_UNSUPPORTED");
            this.blocked = List.copyOf(blocked);
        }
        public String code() { return "WORKFLOW_TRIAL_GRAPH_UNSUPPORTED"; }
        public List<BlockedNode> blocked() { return blocked; }
        public String userMessage() { return describe(blocked); }
    }

    /** Control-approved owner facts per node; Runtime re-reads and compares before anything dispatches. */
    public record AllowedTarget(String nodeId, long apiId, String qualifiedName,
                                String environment, String acceptedContractHash, String sourceSetRevision,
                                String assetType, String methodName, String sourceContractHash,
                                String executionRevision) {
        public static AllowedTarget httpApi(Target target, String environment, String acceptedContractHash,
                                            String sourceSetRevision) {
            return new AllowedTarget(target.nodeId(), target.apiId(), target.qualifiedName(), environment,
                    acceptedContractHash, sourceSetRevision, HTTP_API, null, null, null);
        }
        public static AllowedTarget businessMethod(Target target,
                ConsoleCapabilityInvocationContracts.InvocationContext owner) {
            return new AllowedTarget(target.nodeId(), 0, target.qualifiedName(), null,
                    owner.acceptedContractHash(), null, BUSINESS_METHOD, owner.name(), owner.sourceContractHash(),
                    owner.executionRevision());
        }
        public boolean sameTarget(Target target) {
            return target != null && Objects.equals(nodeId, target.nodeId()) && apiId == target.apiId()
                    && Objects.equals(qualifiedName, target.qualifiedName())
                    && Objects.equals(assetType, target.assetType());
        }
    }

    /** Browser input never supplies any of these attested identity/target/hash fields. */
    public record TrialCommand(int contractVersion, String workflowId, String expectedRevision,
                               String graphSha256, Long projectId, String projectCode,
                               String platformActorId, List<AllowedTarget> allowedTargets,
                               String message, Map<String, Object> inputParams, long deadlineEpochMs) { }

    /**
     * Request-local only; never persisted as a release pin or placed in GraphSpec. {@code owners} maps each pinned
     * business-method node id to the owner facts Control approved and Runtime re-read.
     */
    public record BusinessMethodTrialScope(Long projectId, String projectCode,
                                           Map<String, ConsoleCapabilityInvocationContracts.InvocationContext> owners,
                                           long deadlineEpochMs) {
        public BusinessMethodTrialScope {
            owners = owners == null ? Map.of() : Map.copyOf(owners);
        }
    }

    /** Classifies every node of the saved graph; throws {@link GraphRejected} naming each ineligible node. */
    public static Plan plan(ObjectMapper json, String graphJson) {
        JsonNode nodes;
        try {
            nodes = json.readTree(graphJson).path("nodes");
        } catch (Exception invalid) {
            throw new GraphRejected(List.of(new BlockedNode("", "", "", "草稿 GraphSpec 无法解析")));
        }
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw new GraphRejected(List.of(new BlockedNode("", "", "", "草稿没有可执行节点")));
        }
        List<Target> targets = new ArrayList<>();
        List<BlockedNode> blocked = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            String id = node.path("id").asText("");
            String name = node.path("name").asText("");
            String type = node.path("type").asText("").trim().toUpperCase(Locale.ROOT);
            if (!id.matches("[A-Za-z0-9_-]{1,128}") || !ids.add(id)) {
                blocked.add(new BlockedNode(id, name, type, "节点 ID 无效或重复"));
                continue;
            }
            if ("TOOL".equals(type)) {
                String reason = classifyTool(node, id, name, targets);
                if (reason != null) blocked.add(new BlockedNode(id, name, type, reason));
            } else if ("HTTP_REQUEST".equals(type)) {
                String reason = rawHttpRejection(node);
                if (reason != null) blocked.add(new BlockedNode(id, name, type, reason));
            } else if ("INTERACTION".equals(type)) {
                String interaction = node.path("config").path("interactionType").asText("")
                        .trim().replace('-', '_').toUpperCase(Locale.ROOT);
                if (!"PRESENT_OUTPUT".equals(interaction)) {
                    blocked.add(new BlockedNode(id, name, type, "需要人工交互，试运行不会挂起等待"));
                }
            } else if (!PURE_NODE_TYPES.contains(type)) {
                blocked.add(new BlockedNode(id, name, type, switch (type) {
                    case "PAGE_ACTION" -> "会操作用户页面，可能产生副作用";
                    case "HUMAN_APPROVAL" -> "需要人工审批，试运行不会挂起等待";
                    case "MCP_CALL" -> "外部 MCP 工具的副作用无法确认";
                    case "KNOWLEDGE_WRITE" -> "会写入知识库";
                    default -> "节点类型不在只读试运行允许范围内";
                }));
            }
        }
        if (blocked.isEmpty() && targets.size() > MAX_TARGETS) {
            blocked.add(new BlockedNode("", "", "TOOL", "业务方法/API 节点超过 " + MAX_TARGETS + " 个"));
        }
        if (!blocked.isEmpty()) throw new GraphRejected(blocked);
        return new Plan(targets);
    }

    private static String classifyTool(JsonNode node, String id, String name, List<Target> targets) {
        String qualifiedName = resolveQualifiedName(node);
        JsonNode config = node.path("config");
        if (qualifiedName == null) return "未选择业务方法或 API";
        // Same branch rule as the Runtime TOOL handler: an http-api reference or an API asset id is an HTTP API.
        if (qualifiedName.startsWith("http-api:") || config.has("httpApiAssetId")) {
            JsonNode apiId = config.path("httpApiAssetId");
            if (!qualifiedName.matches("http-api:[A-Za-z0-9._:-]{1,240}") || !apiId.isIntegralNumber()
                    || apiId.longValue() <= 0) return "API 节点缺少已接纳的 API 引用，请重新选择 API 并保存";
            targets.add(new Target(id, name, apiId.longValue(), qualifiedName, HTTP_API));
            return null;
        }
        if (!qualifiedName.matches("[A-Za-z0-9._-]+:[A-Za-z0-9._:-]{1,180}")) {
            return "业务方法引用无效，请重新选择业务方法并保存";
        }
        targets.add(new Target(id, name, 0, qualifiedName, BUSINESS_METHOD));
        return null;
    }

    /** Raw HTTP_REQUEST is only a plain read when it is a credential-free GET/HEAD. */
    private static String rawHttpRejection(JsonNode node) {
        Map<String, JsonNode> config = new LinkedHashMap<>();
        node.path("config").fields().forEachRemaining(entry -> config.put(entry.getKey(), entry.getValue()));
        node.path("config").path("httpConfig").fields().forEachRemaining(entry -> config.put(entry.getKey(), entry.getValue()));
        if (config.containsKey("marketRef")) return "旧市场 HTTP 请求不能试运行，请改用 API 节点";
        String method = config.getOrDefault("method", com.fasterxml.jackson.databind.node.TextNode.valueOf("GET"))
                .asText("GET").trim().toUpperCase(Locale.ROOT);
        if (method.isEmpty()) method = "GET";
        if (!Set.of("GET", "HEAD").contains(method)) return "HTTP " + method + " 请求可能写入外部系统";
        JsonNode credential = config.get("credentialRef");
        if (credential != null && !credential.isNull() && !credential.asText("").isBlank()) {
            return "使用凭据的原始 HTTP 请求不能用项目测试身份试运行，请改用 API 节点";
        }
        return null;
    }

    /** JSON mirror of the Runtime {@code GraphSpecToolContract.resolve} order. */
    static String resolveQualifiedName(JsonNode node) {
        String ref = first(node.path("ref").path("qualifiedName"), node.path("ref").path("name"));
        if (ref != null) return ref;
        JsonNode config = node.path("config");
        String direct = configured(config, 0);
        if (direct != null) return direct;
        return config.path("toolConfig").isObject() ? configured(config.path("toolConfig"), 0) : null;
    }

    private static String configured(JsonNode value, int depth) {
        if (depth > 4 || !value.isObject()) return null;
        String direct = first(value.path("qualifiedName"));
        if (direct != null) return direct;
        JsonNode ref = value.path("ref");
        String nested = ref.isObject()
                ? firstText(first(ref.path("qualifiedName"), ref.path("name")), configured(ref, depth + 1))
                : first(ref);
        return firstText(nested, first(value.path("toolName")));
    }

    private static String first(JsonNode... values) {
        for (JsonNode value : values) {
            if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText().trim();
        }
        return null;
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    /** Human-readable Chinese reason naming every blocked node (bounded). */
    public static String describe(List<BlockedNode> blocked) {
        if (blocked == null || blocked.isEmpty()) return "草稿暂不能只读试运行";
        StringBuilder text = new StringBuilder("以下节点不能只读试运行：");
        int shown = 0;
        for (BlockedNode node : blocked) {
            if (shown == 5) { text.append(" 等 ").append(blocked.size()).append(" 个节点"); break; }
            if (shown++ > 0) text.append("；");
            String label = node.nodeName() == null || node.nodeName().isBlank() ? node.nodeId() : node.nodeName();
            String type = TYPE_LABELS.getOrDefault(node.nodeType(), node.nodeType());
            if (label != null && !label.isBlank()) {
                text.append("「").append(label).append("」");
                if (type != null && !type.isBlank()) text.append("（").append(type).append("）");
            }
            text.append(node.reason());
        }
        return text.append("。请删除或替换这些节点，或发布后通过正式入口验证。").toString();
    }

    public static String businessOwnerRejection(ConsoleCapabilityInvocationContracts.InvocationContext owner,
                                               Long projectId, String projectCode, String qualifiedName) {
        if (owner == null || owner.contractVersion() != 1 || !BUSINESS_METHOD.equals(owner.assetType())
                || !Objects.equals(projectId, owner.projectId()) || !Objects.equals(projectCode, owner.projectCode())
                || !Objects.equals(qualifiedName, owner.qualifiedName())
                || !Objects.equals(qualifiedName, owner.sourceQualifiedName())) return "BUSINESS_METHOD_TRIAL_OWNER_CHANGED";
        if (!owner.enabled()) return "BUSINESS_METHOD_TRIAL_DISABLED";
        if (!"READY".equals(owner.sourceAvailability()) || owner.currentContractHash() == null
                || !owner.currentContractHash().matches("[0-9a-f]{64}")
                || !owner.currentContractHash().equals(owner.acceptedContractHash())
                || !owner.currentContractHash().equals(owner.sourceContractHash())) return "BUSINESS_METHOD_TRIAL_SOURCE_CHANGED";
        if (!"READ_ONLY".equals(owner.sideEffect())) return "BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED";
        if (!owner.credentialAvailable() || owner.executionRevision() == null
                || !owner.executionRevision().matches("[0-9a-f]{64}")) return "BUSINESS_METHOD_TRIAL_CREDENTIAL_REQUIRED";
        if (owner.businessIdentityRequired()) return "BUSINESS_METHOD_TRIAL_BUSINESS_IDENTITY_REQUIRED";
        return owner.executable() ? null : "BUSINESS_METHOD_TRIAL_UNAVAILABLE";
    }

    /** Chinese explanation for an owner rejection code, naming the node. */
    public static String ownerRejectionMessage(String code, String nodeLabel) {
        String node = "「" + (nodeLabel == null ? "" : nodeLabel) + "」";
        return switch (code == null ? "" : code) {
            case "BUSINESS_METHOD_TRIAL_DISABLED" -> node + "的业务方法已停用";
            case "BUSINESS_METHOD_TRIAL_SOURCE_CHANGED" -> node + "的业务方法来源不可用或已接纳契约不是最新";
            case "BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED" -> node + "的业务方法未声明为只读（READ_ONLY），试运行会真实写入，已拒绝";
            case "BUSINESS_METHOD_TRIAL_CREDENTIAL_REQUIRED" -> node + "所属项目的签名凭据不可用";
            case "BUSINESS_METHOD_TRIAL_BUSINESS_IDENTITY_REQUIRED" -> node + "的业务方法要求业务用户身份，项目测试身份不可调用";
            case "BUSINESS_METHOD_TRIAL_UNAVAILABLE" -> node + "的业务方法当前不可执行，请到方法详情核对";
            case "HTTP_API_TRIAL_READ_ONLY_REQUIRED" -> node + "的 API 未声明为只读 GET 操作，试运行会真实写入，已拒绝";
            case "HTTP_API_TRIAL_SOURCE_CHANGED" -> node + "的 API 来源或已接纳契约已变化";
            case "HTTP_API_CONNECTION_NOT_READY" -> node + "的 API 连接尚未配置完成";
            default -> node + "的业务方法/API 所属项目或身份已变化";
        };
    }

    public static String graphSha256(String graph) { return HttpApiDraftTrialPolicy.graphSha256(graph); }
}
