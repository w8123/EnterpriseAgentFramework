package com.enterprise.ai.control.aicoding.retrofit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskRequiredResource;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.VerificationGuideItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.QuestionStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskQuestionMapper;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.FeignException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 业务能力改造：a developer's coding tool reads the business repository, proposes which operations to
 * open to AI, waits for the developer's choice, declares the chosen ones with the ReachAI SDK and
 * triggers one audited SDK sync. ReachAI then judges completion from platform facts (online
 * instance, accepted business methods), never from the coding report alone.
 */
@Component
public class CapabilityRetrofitTaskProvider implements AiCodingTaskKindProvider {

    public static final String TASK_KIND = CapabilityRetrofitProtocol.TASK_KIND;
    public static final String CONTRACT_KEY = "reachai.capability-retrofit-report";
    public static final String CONTRACT_VERSION = "v1";
    public static final String REPORT_SCHEMA = "reachai.capability-retrofit-report.v1";
    public static final String SKILL_NAME = "reachai-capability-retrofit";
    static final Set<String> MODES = Set.of("SDK_DECLARATION", "OPENAPI_IMPORT");

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final AiCodingTaskQuestionMapper questionMapper;
    private final ObjectMapper objectMapper;
    private final TaskContract contract;

    public CapabilityRetrofitTaskProvider(CapabilityProjectOnboardingClient capabilityClient,
                                          AiCodingTaskQuestionMapper questionMapper,
                                          ObjectMapper objectMapper,
                                          AiCodingContractResourceLoader resourceLoader) {
        this.capabilityClient = capabilityClient;
        this.questionMapper = questionMapper;
        this.objectMapper = objectMapper;
        this.contract = new TaskContract(
                TASK_KIND,
                TASK_KIND,
                "READ_WRITE",
                "PROJECT",
                CONTRACT_KEY,
                CONTRACT_VERSION,
                resourceLoader.load("capability-retrofit-report-v1.schema.json"),
                resourceLoader.load("capability-retrofit-report-v1.example.json"));
    }

    @Override
    public String kind() {
        return TASK_KIND;
    }

    @Override
    public TaskContract contract() {
        return contract;
    }

    @Override
    public JsonNode buildContext(TaskDescriptor task) {
        Map<String, Object> facts = readinessFacts(task);
        String language = sdkLanguage(task, facts);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema", "reachai.ai-coding.capability-retrofit-context.v1");
        ObjectNode project = root.putObject("project");
        project.put("projectId", task.projectId());
        project.put("projectCode", task.projectCode());
        root.put("objective", task.objective());
        root.put("sdkLanguage", language);
        ObjectNode onboarding = root.putObject("onboarding");
        boolean online = Boolean.TRUE.equals(facts.get("online"));
        onboarding.put("instanceOnline", online);
        onboarding.put("lastHeartbeatAt", facts.get("lastHeartbeatAt") == null ? null : String.valueOf(facts.get("lastHeartbeatAt")));
        onboarding.put("guidance", online
                ? "业务系统已接入并在线，可以直接改造。"
                : "当前没有在线的 SDK 实例。若业务仓库还没有接入 ReachAI SDK，请先停下，用提问告诉开发者先完成「项目接入」任务；若已接入只是没启动，改完代码后启动服务再做 SDK 同步。");

        ObjectNode scope = root.putObject("scope");
        scope.put("accessMode", "READ_WRITE");
        scope.putArray("includedRules")
                .add("只修改当前业务仓库里被选中的业务方法的能力声明，以及声明所需的 DTO 字段元数据。")
                .add("名称、说明一律写清晰的简体中文，参数说明写业务含义。")
                .add("会改数据的操作必须标 WRITE；删除、作废、付款这类无法撤回的标 IRREVERSIBLE。");
        scope.putArray("excludedRules")
                .add("不改业务逻辑、事务、权限校验和数据库结构。")
                .add("不开放登录、注册、密码、文件上传下载、导入导出、批量删除、系统配置、定时任务和框架自带接口。")
                .add("没有被开发者勾选的候选，一行代码都不要动。")
                .add("不打印、不提交任何密钥、激活码或任务令牌。")
                .add("不要在启动时自动扫描或同步能力；只在本任务里触发一次 SDK_SYNC。");

        ArrayNode phases = root.putArray("phases");
        phase(phases, "INVENTORY", "盘点",
                "只读扫描业务代码，找出符合目标的业务方法（Service 层优先，其次 Controller）。已经有能力声明的不要重复列。"
                        + "把候选清单作为一个问题提交：questionId 以 " + CapabilityRetrofitProtocol.SELECTION_QUESTION_PREFIX
                        + " 开头，title 为「" + CapabilityRetrofitProtocol.SELECTION_QUESTION_TITLE + "」，body 是 candidateDocument 格式的 JSON 字符串，options 是全部 candidateKey。");
        phase(phases, "WAIT_SELECTION", "等开发者勾选",
                "开发者会在 ReachAI 页面勾选，或在你的对话里直接告诉你选哪几个。读取问题的 answer（selectionDocument 格式，或逗号分隔的 candidateKey）后写 RESUMED 事件再继续。"
                        + "只有开发者在对话里明确说了选哪些，你才可以代为回答这个问题，不要自己替开发者决定。");
        phase(phases, "IMPLEMENT", "实施",
                "按 declarationGuide 给选中的方法加声明，编译并跑相关测试，重启业务服务。");
        phase(phases, "SDK_SYNC", "同步",
                "实例心跳正常后触发一次 SDK_SYNC 校验。新能力会进入 ReachAI 的能力变化评审，等人接受。");
        phase(phases, "REPORT", "交付",
                "提交一份 " + REPORT_SCHEMA + " 报告。ReachAI 会核对能力是否真的进入了评审或已被接受，报告本身不算完成。");

        root.set("candidateDocument", candidateExample());
        root.set("selectionDocument", selectionExample());
        root.set("declarationGuide", declarationGuide(language));
        ObjectNode noCode = root.putObject("noCodeOption");
        noCode.put("mode", "OPENAPI_IMPORT");
        noCode.put("whenToUse", "开发者明确要求不改业务代码，或系统不是五种 SDK 支持的语言时。");
        noCode.put("how", "按选中的候选生成 OpenAPI 3 文档（只包含选中的接口，中文 summary/description，写操作标 x-reachai-side-effect），"
                + "放在仓库 docs/reachai/openapi.yaml；请开发者在 ReachAI 项目「业务能力 → HTTP API → 导入 OpenAPI」导入并接受。报告里 mode 写 OPENAPI_IMPORT，"
                + "capabilities[].name 写接口在 ReachAI 里的 keySlug（导入后可在 HTTP API 列表看到），sdkSync.status 写 NOT_RUN。");
        root.put("sdkSyncVerificationKey", "SDK_SYNC");
        root.put("acceptanceBoundary", "代码改完只算交付了报告。能力在 ReachAI 评审里被接受、实例在线，任务才会进入验收。");
        return root;
    }

    @Override
    public JsonNode materializeContext(TaskDescriptor task, JsonNode contextSnapshot, String publicBaseUrl) {
        ObjectNode root = contextSnapshot != null && contextSnapshot.isObject()
                ? ((ObjectNode) contextSnapshot).deepCopy()
                : objectMapper.createObjectNode();
        String platformRoot = platformRoot(publicBaseUrl);
        root.put("platformBaseUrl", platformRoot);
        root.put("skillPackageUrl", platformRoot + "/api/ai-assist/skills/" + SKILL_NAME + "/latest.zip");
        return root;
    }

    @Override
    public List<ReadinessItem> readiness(TaskDescriptor task) {
        return readiness(task, null);
    }

    @Override
    public List<ReadinessItem> readiness(TaskDescriptor task, JsonNode applicationResult) {
        List<ReadinessItem> items = new ArrayList<>();
        Selection selection = selection(task.taskId());
        items.add(new ReadinessItem("SELECTION_CONFIRMED", "开发者已勾选候选能力",
                selection == null ? "PENDING" : selection.answered() ? "PASS" : "PENDING",
                selection == null ? "还没有提交候选清单。"
                        : selection.answered() ? "已选 " + selection.selected().size() + " 项：" + String.join("、", selection.selected())
                        : "候选清单已提交，等开发者勾选。",
                null));
        Map<String, Object> facts = readinessFacts(task);
        boolean online = Boolean.TRUE.equals(facts.get("online"));
        items.add(new ReadinessItem("RUNTIME_READY", "业务实例在线",
                online ? "PASS" : "PENDING",
                online ? "最近心跳 " + facts.get("lastHeartbeatAt") : "没有在线的 SDK 实例，改完代码后需要启动业务服务。",
                null));
        JsonNode report = applicationResult == null ? null : applicationResult.get("codingReport");
        if (report == null || !report.isObject()) {
            items.add(new ReadinessItem("CAPABILITIES_ACCEPTED", "新能力已在评审中接受",
                    "PENDING", "等编码工具提交改造报告。", null));
            return items;
        }
        List<String> names = declaredNames(report);
        String mode = CapabilityRetrofitProtocol.text(report.get("mode"));
        items.add(capabilitiesAccepted(task, mode, names));
        return items;
    }

    @Override
    public List<String> acceptanceReadinessKeys() {
        return List.of("SELECTION_CONFIRMED", "CAPABILITIES_ACCEPTED");
    }

    @Override
    public List<TaskRequiredResource> requiredResources(TaskDescriptor task, String publicBaseUrl) {
        return List.of(new TaskRequiredResource(
                SKILL_NAME + "-skill",
                "ReachAI 业务能力改造 Skill",
                "SKILL_ZIP",
                platformRoot(publicBaseUrl) + "/api/ai-assist/skills/" + SKILL_NAME + "/latest.zip",
                SKILL_NAME + "/SKILL.md",
                "包含盘点规则、五种语言的能力声明写法、候选清单格式和报告格式。",
                true));
    }

    @Override
    public List<VerificationGuideItem> verificationGuide(TaskDescriptor task, String taskRoot) {
        return List.of(
                new VerificationGuideItem(
                        "SDK_SYNC",
                        "ACTION",
                        "触发签名 SDK 同步",
                        "让 ReachAI 对当前项目做一次审计过的 SDK 回调，读取刚加的能力声明；新能力进入能力变化评审。",
                        "POST",
                        taskRoot + "/verifications/SDK_SYNC",
                        List.of("任务状态为 RUNNING", "开发者已勾选候选", "业务服务已用新代码重启且心跳在线"),
                        List.of("CAPABILITIES_ACCEPTED"),
                        "返回能力快照，或稳定的失败原因（不可达、401/403、404、签名被拒）。",
                        "Capability 服务记录的签名回调与能力快照"),
                new VerificationGuideItem(
                        "CAPABILITIES_ACCEPTED",
                        "READINESS_GATE",
                        "新能力已被接受",
                        "报告里声明的每个能力都在 ReachAI 能力评审中被人接受后才通过；这一步由人操作，编码工具不能代替。",
                        null,
                        null,
                        List.of("已提交改造报告", "已执行 SDK_SYNC"),
                        List.of("CAPABILITIES_ACCEPTED"),
                        "readiness.status=PASS。",
                        "Capability 服务里的业务方法接受状态"));
    }

    @Override
    public JsonNode requestVerification(TaskDescriptor task, String verificationKey) {
        if (!"SDK_SYNC".equals(verificationKey)) {
            return AiCodingTaskKindProvider.super.requestVerification(task, verificationKey);
        }
        Selection selection = selection(task.taskId());
        if (selection == null || !selection.answered()) {
            throw new IllegalStateException("开发者还没有勾选候选能力，不能同步。");
        }
        try {
            return objectMapper.valueToTree(capabilityClient.triggerSdkSync(task.projectId()));
        } catch (FeignException ex) {
            throw new IllegalStateException("ReachAI 能力服务没能完成 SDK 同步：" + ex.status(), ex);
        }
    }

    @Override
    public ArtifactApplyResult applyArtifact(TaskDescriptor task, ArtifactEnvelope artifact) {
        JsonNode report = artifact == null ? null : artifact.content();
        if (report == null || !report.isObject() || !REPORT_SCHEMA.equals(CapabilityRetrofitProtocol.text(report.get("schema")))) {
            throw new IllegalArgumentException("schema must be " + REPORT_SCHEMA);
        }
        if (!StringUtils.hasText(CapabilityRetrofitProtocol.text(report.get("summary")))) {
            throw new IllegalArgumentException("summary is required");
        }
        String mode = CapabilityRetrofitProtocol.text(report.get("mode"));
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("mode must be SDK_DECLARATION or OPENAPI_IMPORT");
        }
        String questionId = CapabilityRetrofitProtocol.text(report.get("selectionQuestionId"));
        Selection selection = selection(task.taskId());
        if (selection == null || !selection.answered() || !selection.questionId().equals(questionId)) {
            throw new IllegalArgumentException("selectionQuestionId 必须是本任务里已被开发者回答的候选清单问题");
        }
        List<String> declared = new ArrayList<>();
        for (JsonNode capability : report.path("capabilities")) {
            if (!"DECLARED".equals(CapabilityRetrofitProtocol.text(capability.get("status")))) continue;
            String key = CapabilityRetrofitProtocol.text(capability.get("candidateKey"));
            if (!selection.selected().contains(key)) {
                throw new IllegalArgumentException("能力 " + key + " 不在开发者勾选的范围内，不能声明");
            }
            declared.add(CapabilityRetrofitProtocol.text(capability.get("name")));
        }
        if (declared.isEmpty()) {
            throw new IllegalArgumentException("报告里至少要有一个 status=DECLARED 的能力");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("codingReport", report);
        result.set("selection", objectMapper.valueToTree(selection.selected()));
        ObjectNode platform = result.putObject("platformCheck");
        platform.put("checkedAt", LocalDateTime.now().toString());
        platform.set("capabilityStates", objectMapper.valueToTree(capabilityStates(task, mode, declared)));
        return ArtifactApplyResult.acceptanceRequired(
                "改造报告已保留；请在能力评审中接受新能力，ReachAI 会据此判断是否可以验收", result);
    }

    /** Latest selection question of the task, with its parsed candidates and answer. */
    public Selection selection(String taskId) {
        List<AiCodingTaskQuestionEntity> questions = questionMapper.selectList(Wrappers.<AiCodingTaskQuestionEntity>query()
                .eq("task_id", taskId)
                .likeRight("question_id", CapabilityRetrofitProtocol.SELECTION_QUESTION_PREFIX));
        AiCodingTaskQuestionEntity latest = questions.stream()
                .filter(question -> CapabilityRetrofitProtocol.isSelectionQuestion(question.getQuestionId()))
                .max(Comparator.comparing(question -> question.getAskedAt() == null ? LocalDateTime.MIN : question.getAskedAt()))
                .orElse(null);
        if (latest == null) return null;
        JsonNode candidates = CapabilityRetrofitProtocol.parseCandidates(objectMapper, latest.getBody());
        boolean answered = QuestionStatus.ANSWERED.name().equals(latest.getStatus());
        List<String> selected = List.of();
        if (answered) {
            try {
                selected = CapabilityRetrofitProtocol.parseSelection(objectMapper, latest.getAnswer(),
                        candidates == null ? null : CapabilityRetrofitProtocol.candidateKeys(candidates));
            } catch (IllegalArgumentException invalid) {
                selected = List.of();
            }
        }
        return new Selection(latest.getQuestionId(), latest.getStatus(), candidates, answered && !selected.isEmpty(),
                selected, latest.getAnswer(), latest.getAnsweredBy());
    }

    /** Platform facts for declared names; OPENAPI_IMPORT names are HTTP API key slugs. */
    public List<Map<String, Object>> capabilityStates(TaskDescriptor task, String mode, List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        try {
            if ("OPENAPI_IMPORT".equals(mode)) {
                List<Map<String, Object>> tools = capabilityClient.listProjectTools(task.projectId());
                List<Map<String, Object>> rows = new ArrayList<>();
                for (String name : names) {
                    Map<String, Object> tool = tools == null ? null : tools.stream()
                            .filter(candidate -> name.equals(String.valueOf(candidate.get("keySlug"))))
                            .findFirst().orElse(null);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", name);
                    row.put("state", tool == null ? "NOT_SYNCED" : "ACTIVE".equals(tool.get("status")) ? "ACCEPTED" : "DISABLED");
                    row.put("title", tool == null ? null : tool.get("title"));
                    rows.add(row);
                }
                return rows;
            }
            Map<String, Object> body = capabilityClient.getBusinessMethodStates(task.projectId(), names);
            Object items = body == null ? null : body.get("items");
            if (items instanceof List<?> list) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        map.forEach((key, value) -> row.put(String.valueOf(key), value));
                        rows.add(row);
                    }
                }
                return rows;
            }
            return List.of();
        } catch (FeignException ex) {
            return names.stream().map(name -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("state", "UNKNOWN");
                return row;
            }).toList();
        }
    }

    public static List<String> declaredNames(JsonNode report) {
        List<String> names = new ArrayList<>();
        if (report == null) return names;
        for (JsonNode capability : report.path("capabilities")) {
            if ("DECLARED".equals(CapabilityRetrofitProtocol.text(capability.get("status")))) {
                String name = CapabilityRetrofitProtocol.text(capability.get("name"));
                if (StringUtils.hasText(name)) names.add(name);
            }
        }
        return names;
    }

    private ReadinessItem capabilitiesAccepted(TaskDescriptor task, String mode, List<String> names) {
        List<Map<String, Object>> states = capabilityStates(task, mode, names);
        List<String> waiting = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> state : states) {
            String value = String.valueOf(state.get("state"));
            if ("PENDING_REVIEW".equals(value)) waiting.add(String.valueOf(state.get("name")));
            else if (!"ACCEPTED".equals(value)) missing.add(String.valueOf(state.get("name")));
        }
        String status = !states.isEmpty() && waiting.isEmpty() && missing.isEmpty() ? "PASS" : "PENDING";
        String message;
        if ("PASS".equals(status)) {
            message = "报告里的 " + names.size() + " 个能力都已被接受。";
        } else {
            List<String> parts = new ArrayList<>();
            if (!waiting.isEmpty()) parts.add("待评审：" + String.join("、", waiting));
            if (!missing.isEmpty()) parts.add("还没同步到平台：" + String.join("、", missing));
            message = parts.isEmpty() ? "暂时读不到能力状态。" : String.join("；", parts);
        }
        return new ReadinessItem("CAPABILITIES_ACCEPTED", "新能力已在评审中接受", status, message,
                objectMapper.valueToTree(states));
    }

    private Map<String, Object> readinessFacts(TaskDescriptor task) {
        try {
            Map<String, Object> facts = capabilityClient.getReadinessFacts(task.projectId());
            return facts == null ? Map.of() : facts;
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    static String sdkLanguage(TaskDescriptor task, Map<String, Object> facts) {
        if (task != null && task.targets() != null) {
            for (var target : task.targets()) {
                if (target != null && target.snapshot() != null && target.snapshot().path("sdkLanguage").isTextual()) {
                    return normalizeLanguage(target.snapshot().path("sdkLanguage").asText());
                }
            }
        }
        Object hint = facts == null ? null : facts.get("sdkLanguage");
        return hint == null ? "java" : normalizeLanguage(String.valueOf(hint));
    }

    private static String normalizeLanguage(String language) {
        String value = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        return Set.of("java", "csharp", "python", "node", "go").contains(value) ? value : "unknown";
    }

    private ObjectNode declarationGuide(String language) {
        ObjectNode guide = objectMapper.createObjectNode();
        guide.put("language", language);
        guide.put("reference", "Skill 里的 references/declarations.md 有五种语言的完整写法。");
        switch (language) {
            case "csharp" -> guide.put("example",
                    "[ReachCapability(\"erp.receivable.page\", Title = \"查询应收款\", SideEffect = ReachSideEffect.Read, BusinessIdentityRequired = true)]\n"
                            + "public Task<PageResult<ReceivableVo>> PageAsync([ReachParam(\"customerName\", Description = \"客户名称\")] string? customerName)");
            case "python" -> guide.put("example",
                    "@reach.capability(name=\"erp.receivable.page\", title=\"查询应收款\", side_effect=\"READ\", business_identity_required=True)\n"
                            + "async def page_receivables(customer_name: str | None = None): ...");
            case "node" -> guide.put("example",
                    "reach.capability({ name: 'erp.receivable.page', title: '查询应收款', sideEffect: 'READ', businessIdentityRequired: true,\n"
                            + "  inputSchema: { type: 'object', properties: { customerName: { type: 'string', description: '客户名称' } } },\n"
                            + "  outputSchema: { type: 'object', properties: { total: { type: 'integer' } }, required: ['total'] }, handler: async (args) => ... })");
            case "go" -> guide.put("example",
                    "c.RegisterCapability(reachai.Capability{Name: \"erp.receivable.page\", Title: \"查询应收款\", SideEffect: \"READ\", BusinessIdentityRequired: true,\n"
                            + "  InputSchema: input, Handler: func(ctx context.Context, args map[string]any) (any, error) { ... }})");
            default -> guide.put("example",
                    "@ReachCapability(name = \"erpReceivablePage\", title = \"查询应收款\", description = \"按客户和日期分页查询应收款\", sideEffect = ReachSideEffectLevel.READ, businessIdentityRequired = true)\n"
                            + "public PageResult<ReceivableRespVO> page(@ReachParam(name = \"customerName\", description = \"客户名称\") String customerName, ...)");
        }
        return guide;
    }

    private ObjectNode candidateExample() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema", CapabilityRetrofitProtocol.CANDIDATES_SCHEMA);
        root.put("summary", "按目标找到 2 个适合开放的业务方法。");
        ArrayNode rows = root.putArray("candidates");
        ObjectNode one = rows.addObject();
        one.put("candidateKey", "erp.receivable.page");
        one.put("title", "查询应收款");
        one.put("description", "按客户、日期分页查询应收款");
        one.put("sideEffect", "READ");
        one.put("sourceFile", "src/main/java/.../ErpReceivableServiceImpl.java");
        one.put("symbol", "ErpReceivableServiceImpl#getReceivablePage");
        one.put("httpMethod", "GET");
        one.put("path", "/erp/receivable/page");
        one.put("reason", "目标里提到查应收，且该方法已有租户和数据权限校验。");
        one.put("risk", "");
        one.put("recommended", true);
        root.put("limits", "最多 " + CapabilityRetrofitProtocol.MAX_CANDIDATES + " 项；sideEffect 只能是 READ/WRITE/IRREVERSIBLE。");
        return root;
    }

    private ObjectNode selectionExample() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema", CapabilityRetrofitProtocol.SELECTION_SCHEMA);
        root.putArray("selected").add("erp.receivable.page");
        root.put("note", "退货先不开放。");
        return root;
    }

    private static void phase(ArrayNode phases, String key, String title, String description) {
        ObjectNode phase = phases.addObject();
        phase.put("key", key);
        phase.put("title", title);
        phase.put("description", description);
    }

    private static String platformRoot(String publicBaseUrl) {
        return publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public record Selection(String questionId, String status, JsonNode candidates, boolean answered,
                            List<String> selected, String rawAnswer, String answeredBy) {
    }
}
