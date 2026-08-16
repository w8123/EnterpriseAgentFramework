package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskRequiredResource;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.VerificationGuideItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingDeliveryEvidence.BrowserVerification;
import com.enterprise.ai.control.aicoding.domain.AiCodingDeliveryEvidence.ReportedCheck;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedConversationEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.FeignException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ProjectOnboardingTaskProvider implements AiCodingTaskKindProvider {

    public static final String TASK_KIND = "PROJECT_ONBOARDING";
    public static final String CONTRACT_KEY = "reachai.project-onboarding-report";
    public static final String CONTRACT_VERSION = "v1";

    private static final List<String> STEP_KEYS = List.of(
            "PROJECT",
            "STARTER",
            "GATEWAY",
            "BUSINESS_API",
            "EMBED_TOKEN",
            "FINAL_CHECK");
    private static final Set<String> FINAL_STEP_STATUSES = Set.of(
            "PASS",
            "WARN",
            "FAIL",
            "SKIPPED");

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final PlatformEmbedE2eEvidenceService embedE2eEvidenceService;
    private final ObjectMapper objectMapper;
    private final TaskContract contract;

    public ProjectOnboardingTaskProvider(
            CapabilityProjectOnboardingClient capabilityClient,
            PlatformEmbedE2eEvidenceService embedE2eEvidenceService,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resourceLoader) {
        this.capabilityClient = capabilityClient;
        this.embedE2eEvidenceService = embedE2eEvidenceService;
        this.objectMapper = objectMapper;
        this.contract = new TaskContract(
                "PROJECT_ONBOARDING",
                TASK_KIND,
                "READ_WRITE",
                "PROJECT",
                CONTRACT_KEY,
                CONTRACT_VERSION,
                resourceLoader.load("project-onboarding-report-v1.schema.json"),
                resourceLoader.load("project-onboarding-report-v1.example.json"));
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
        Map<String, Object> source = capabilityClient.getOnboardingProjectById(
                task.projectId());
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema", "reachai.ai-coding.project-onboarding-context.v1");
        root.set("project", safeProject(source, task));

        ObjectNode scope = root.putObject("scope");
        scope.put("accessMode", "READ_WRITE");
        scope.putArray("includedRules")
                .add("Modify only the current business repository and its explicit gateway/frontend modules.")
                .add("Add ReachAI project dependencies and configuration visibly in normal project files.")
                .add("Use the supplied contracts instead of inventing endpoints or response shapes.");
        scope.putArray("excludedRules")
                .add("Do not scan or modify unrelated repositories or modules.")
                .add("Do not install ReachAI Runner, resident processes, global npm packages or system software.")
                .add("Do not print, persist or return credentials, activation codes or task tokens.")
                .add("Do not claim runtime or E2E success without observed evidence.");
        scope.putArray("requiredChecks")
                .add("Run focused backend/frontend tests for every changed module.")
                .add("Start or restart the business service and verify ReachAI instance heartbeat.")
                .add("Exercise the authorized Token Broker, Embed proxy, session and message path through a real business page or reachai-doctor with business-supplied test authorization.")
                .add("Before final user acceptance, open a real business page and verify the ReachAI entry is visible and usable.")
                .add("If browser verification is performed, report its exact URL, scenarios, screenshot path and observed result.")
                .add("Submit exactly one final project-onboarding artifact through this task.");

        ArrayNode steps = root.putArray("canonicalSteps");
        addStep(steps, "PROJECT", "项目识别",
                "Confirm repository modules, projectCode, environment and existing ReachAI integration.");
        addStep(steps, "STARTER", "后端 Starter",
                "Add the ReachAI starter/capability SDK and declare registration configuration.");
        addStep(steps, "GATEWAY", "网关路由",
                "Wire embed proxy and registry callback routes without mixing business JWT and embed auth.");
        addStep(steps, "BUSINESS_API", "业务服务校验",
                "Expose and register real business capabilities with domain authorization preserved.");
        addStep(steps, "EMBED_TOKEN", "前端 Embed Token",
                "Broker short-lived embed tokens behind business login and integrate the browser package.");
        addStep(steps, "FINAL_CHECK", "测试与浏览器验收",
                "Run tests, service heartbeat checks, authorized conversation E2E and final visible browser acceptance.");

        root.set(
                "sdkArtifacts",
                objectMapper.valueToTree(ControlAiAssistProjectController.sdkArtifacts()));
        root.set(
                "gatewayChecklist",
                objectMapper.valueToTree(ControlAiAssistProjectController.gatewayChecklist()));
        Map<String, ControlAiAssistProjectController.ResponseShape> shapes =
                new LinkedHashMap<>(ControlAiAssistProjectController.responseShapes());
        shapes.remove("aiAccessSessions");
        root.set("responseShapes", objectMapper.valueToTree(shapes));

        ObjectNode implementation = root.putObject("implementationGuidance");
        implementation.put("objective", task.objective());
        implementation.put("secretEnvironmentVariable", "REACHAI_REGISTRY_APP_SECRET");
        implementation.put(
                "secretSetupCommandTemplate",
                "powershell -NoProfile -ExecutionPolicy Bypass -File "
                        + "\"{skillExtractDir}/reachai-onboarding/scripts/"
                        + "set-reachai-registry-secret.ps1\" -Target User");
        implementation.put(
                "sdkSyncVerificationKey",
                "SDK_SYNC");
        implementation.put("callbackPath", "/reachai/registry/capabilities/sync");
        implementation.put("embedProxyPath", "/api/reachai/embed/**");
        implementation.put(
                "embedTokenExchangeClient",
                "Use the Starter bean com.enterprise.ai.reach.spring.ReachAiEmbedTokenClient. "
                        + "The business broker only resolves its authenticated user and forwards the SDK-owned page identity; "
                        + "do not reimplement ReachAI signing or wrapped response parsing.");
        implementation.put(
                "projectCopilotKeySlug",
                ControlProjectAgentProvisioningService.pageCopilotKeySlug(
                        task.projectCode(),
                        task.projectId()));
        implementation.put(
                "projectCopilotProvisioning",
                "ReachAI control plane owns idempotent Agent/Supervisor provisioning. Use the supplied key slug in business browser configuration; do not request or embed a project-level AI Coding key.");
        ObjectNode pageIdentity = implementation.putObject(
                "embedPageIdentityContract");
        pageIdentity.put(
                "source",
                "@reachai/embed-chat tokenProvider context");
        pageIdentity.putArray("requiredFields")
                .add("pageKey")
                .add("pageInstanceId")
                .add("route")
                .add("origin");
        pageIdentity.put(
                "forwardingRule",
                "Forward tokenProvider context fields unchanged through the business token broker to ReachAI token exchange.");
        pageIdentity.put(
                "fallbackRule",
                "Never generate a replacement pageInstanceId in the browser token provider, business broker or platform client.");
        implementation.put("acceptanceBoundary",
                "Coding completion only submits a report. ReachAI platform checks and browser acceptance close the task.");
        ObjectNode deliveryEvidence = implementation.putObject(
                "deliveryEvidencePolicy");
        deliveryEvidence.put(
                "browserNotRun",
                "Set browserVerification to null when a real browser was not exercised.");
        deliveryEvidence.put(
                "browserAcceptancePrerequisite",
                "Use an existing authorized business-system test session or account supplied outside ReachAI. "
                        + "If it is unavailable, do not request credentials in task artifacts or fabricate evidence; "
                        + "set browserVerification to null and report NOT RUN.");
        deliveryEvidence.put(
                "browserRun",
                "When browserVerification is present, include the exact business URL, at least one scenario, at least one screenshot path and the visible observed result.");
        deliveryEvidence.put(
                "authority",
                "Reported tests and browser evidence are review material only; platform readiness and user acceptance remain authoritative.");
        return root;
    }

    @Override
    public JsonNode materializeContext(
            TaskDescriptor task,
            JsonNode contextSnapshot,
            String publicBaseUrl) {
        ObjectNode root = contextSnapshot != null && contextSnapshot.isObject()
                ? ((ObjectNode) contextSnapshot).deepCopy()
                : objectMapper.createObjectNode();
        root.set(
                "sdkArtifacts",
                objectMapper.valueToTree(
                        ControlAiAssistProjectController.sdkArtifacts(
                                publicBaseUrl)));
        ObjectNode implementation = root.withObject("/implementationGuidance");
        String platformRoot = publicBaseUrl == null
                ? ""
                : publicBaseUrl.replaceAll("/+$", "");
        implementation.put("platformBaseUrl", platformRoot);
        implementation.put(
                "skillPackageUrl",
                platformRoot
                        + "/api/ai-assist/skills/reachai-onboarding/latest.zip");
        return root;
    }

    @Override
    public List<ReadinessItem> readiness(TaskDescriptor task) {
        ControlAiAssistProjectController.SdkAccessCheckResponse check =
                currentCheck(task);
        return check.readiness().stream()
                .map(item -> new ReadinessItem(
                        item.key(),
                        item.label(),
                        item.status(),
                        item.message(),
                        null))
                .toList();
    }

    @Override
    public List<String> acceptanceReadinessKeys() {
        return List.of(
                "CODE_READY",
                "RUNTIME_READY",
                "SDK_CALLBACK_READY",
                "E2E_READY");
    }

    @Override
    public List<TaskRequiredResource> requiredResources(
            TaskDescriptor task,
            String publicBaseUrl) {
        String platformRoot = publicBaseUrl == null
                ? ""
                : publicBaseUrl.replaceAll("/+$", "");
        return List.of(new TaskRequiredResource(
                "reachai-onboarding-skill",
                "ReachAI SDK 接入 Skill",
                "SKILL_ZIP",
                platformRoot + "/api/ai-assist/skills/reachai-onboarding/latest.zip",
                "reachai-onboarding/SKILL.md",
                "包含 SDK 安装、网关认证边界、Embed Token Broker、浏览器验收和 doctor 用法。",
                true));
    }

    @Override
    public List<VerificationGuideItem> verificationGuide(
            TaskDescriptor task,
            String taskRoot) {
        return List.of(
                new VerificationGuideItem(
                        "SDK_SYNC",
                        "ACTION",
                        "触发签名 SDK 同步",
                        "请求 ReachAI 对当前项目执行一次审计过的 SDK 回调；它不是启动时自动扫描。",
                        "POST",
                        taskRoot + "/verifications/SDK_SYNC",
                        List.of("任务状态为 RUNNING", "CODE_READY 已满足", "RUNTIME_READY 为 PASS"),
                        List.of("SDK_CALLBACK_READY"),
                        "返回 SDK callback 的能力快照或稳定错误原因。",
                        "Capability owning service 记录的签名回调与 capability snapshot"),
                new VerificationGuideItem(
                        "SDK_CALLBACK_READY",
                        "READINESS_GATE",
                        "SDK 回调闭环",
                        "只有签名回调成功且平台收到本次 capability snapshot 才会通过；推导出的 URL 不构成通过。",
                        null,
                        null,
                        List.of("已执行 SDK_SYNC 或 API 管理手动同步"),
                        List.of("SDK_CALLBACK_READY"),
                        "readiness.status=PASS。",
                        "Capability owning service 的 callback 状态与快照事实"),
                new VerificationGuideItem(
                        "EMBED_CONVERSATION_E2E",
                        "OBSERVATION",
                        "授权 Embed 会话观察",
                        "使用已有授权的业务测试会话打开真实业务页，或让 reachai-doctor 使用业务方提供的测试 Authorization/Cookie，确认 Token Broker、代理、会话、用户消息和助手回复；ReachAI 不签发或伪造业务登录态。",
                        null,
                        null,
                        List.of("业务系统提供授权浏览器会话或最小权限测试 Authorization/Cookie", "SDK 与网关已部署到目标环境"),
                        List.of("E2E_READY"),
                        "平台在当前任务启动后观察到授权 Session、用户消息和助手回复；doctor PASS 不替代最终页面可见性验收。",
                        "Control 服务端 Embed session/message/reply 记录；不采信客户端自报布尔值或 Mock 登录"),
                new VerificationGuideItem(
                        "E2E_READY",
                        "READINESS_GATE",
                        "授权 Embed 协议闭环",
                        "这是由真实业务授权支撑的平台观察门禁，不是可直接 POST 的 verification key；最终页面体验仍由用户验收。",
                        null,
                        null,
                        List.of("EMBED_CONVERSATION_E2E 已被平台观察到"),
                        List.of("E2E_READY"),
                        "readiness.status=PASS；否则保留 RESULT_APPLIED 并等待重新验证。",
                        "当前任务启动后的平台观察事实"));
    }

    @Override
    public JsonNode requestVerification(
            TaskDescriptor task,
            String verificationKey) {
        if (!"SDK_SYNC".equals(verificationKey)) {
            return AiCodingTaskKindProvider.super.requestVerification(
                    task,
                    verificationKey);
        }
        try {
            return objectMapper.valueToTree(
                    capabilityClient.triggerSdkSync(task.projectId()));
        } catch (FeignException ex) {
            String message = dependencyMessage(ex);
            throw new IllegalStateException(
                    StringUtils.hasText(message)
                            ? message
                            : "ReachAI capability service could not complete SDK sync.",
                    ex);
        }
    }

    @Override
    public ArtifactApplyResult applyArtifact(
            TaskDescriptor task,
            ArtifactEnvelope artifact) {
        ProjectOnboardingReport report;
        try {
            report = objectMapper.treeToValue(
                    artifact.content(),
                    ProjectOnboardingReport.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "Project-onboarding artifact does not match "
                            + CONTRACT_KEY + "/" + CONTRACT_VERSION,
                    ex);
        }
        validate(report);

        ControlAiAssistProjectController.SdkAccessCheckResponse platformCheck =
                currentCheck(task);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("codingReport", artifact.content());
        ObjectNode platform = objectMapper.valueToTree(platformCheck);
        platform.put("checkedAt", LocalDateTime.now().toString());
        result.set("platformCheck", platform);
        return ArtifactApplyResult.acceptanceRequired(
                "项目接入报告已保留；ReachAI 已追加当前平台检查，等待真实运行与浏览器验收",
                result);
    }

    private void validate(ProjectOnboardingReport report) {
        if (report == null) {
            throw new IllegalArgumentException(
                    "project-onboarding report is required");
        }
        if (!"reachai.project-onboarding-report.v1".equals(report.schema())) {
            throw new IllegalArgumentException(
                    "schema must be reachai.project-onboarding-report.v1");
        }
        if (!StringUtils.hasText(report.summary())) {
            throw new IllegalArgumentException("summary is required");
        }
        List<ProjectOnboardingStep> steps =
                report.steps() == null ? List.of() : report.steps();
        if (steps.size() != STEP_KEYS.size()) {
            throw new IllegalArgumentException(
                    "steps must contain exactly the six canonical onboarding steps");
        }
        Set<String> seen = new HashSet<>();
        for (ProjectOnboardingStep step : steps) {
            if (step == null || !StringUtils.hasText(step.stepKey())) {
                throw new IllegalArgumentException("steps[].stepKey is required");
            }
            String key = step.stepKey().trim().toUpperCase();
            if (!STEP_KEYS.contains(key) || !seen.add(key)) {
                throw new IllegalArgumentException(
                        "invalid or duplicate onboarding step: " + key);
            }
            String status = step.status() == null
                    ? ""
                    : step.status().trim().toUpperCase();
            if (!FINAL_STEP_STATUSES.contains(status)) {
                throw new IllegalArgumentException(
                        "step " + key + " status must be PASS, WARN, FAIL or SKIPPED");
            }
            if (!StringUtils.hasText(step.message())) {
                throw new IllegalArgumentException(
                        "step " + key + " message is required");
            }
        }
    }

    private ControlAiAssistProjectController.SdkAccessCheckResponse currentCheck(
            TaskDescriptor task) {
        Map<String, Object> project =
                capabilityClient.getOnboardingProjectById(task.projectId());
        Map<String, Object> facts =
                capabilityClient.getReadinessFacts(task.projectId());
        LocalDateTime observedAfter = task.startedAt() == null
                ? task.createdAt()
                : task.startedAt();
        EmbedConversationEvidence e2eEvidence =
                embedE2eEvidenceService.latestSuccessfulConversation(
                        task.projectCode(),
                        observedAfter);
        boolean artifactAvailable =
                ControlEmbedChatArtifactSupport.integritySha256() != null
                        && ControlJavaSdkArtifactSupport.integritySha256(
                        ControlJavaSdkArtifactSupport.CAPABILITY_SDK,
                        "jar") != null
                        && ControlJavaSdkArtifactSupport.integritySha256(
                        ControlJavaSdkArtifactSupport.SPRING_BOOT2_STARTER,
                        "jar") != null;
        return ControlSdkAccessReadinessCalculator.calculate(
                project == null ? Map.of() : project,
                facts == null ? Map.of() : facts,
                artifactAvailable,
                artifactAvailable
                        ? ControlEmbedChatArtifactSupport.downloadUrl("")
                        : null,
                e2eEvidence);
    }

    private String dependencyMessage(FeignException ex) {
        if (ex == null || !StringUtils.hasText(ex.contentUTF8())) {
            return null;
        }
        try {
            JsonNode body = objectMapper.readTree(ex.contentUTF8());
            JsonNode message = body == null ? null : body.get("message");
            return message == null || message.isNull()
                    ? null
                    : message.asText();
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private ObjectNode safeProject(
            Map<String, Object> source,
            TaskDescriptor task) {
        Map<String, Object> project = source == null ? Map.of() : source;
        ObjectNode safe = objectMapper.createObjectNode();
        safe.put("id", task.projectId());
        safe.put("projectCode", task.projectCode());
        copyText(project, safe, "name");
        copyText(project, safe, "projectKind");
        copyText(project, safe, "environment");
        copyText(project, safe, "baseUrl");
        copyText(project, safe, "contextPath");
        copyText(project, safe, "registryAppKey");
        safe.put(
                "registryCredentialConfigured",
                booleanValue(project.get("registryCredentialConfigured")));
        JsonNode access = objectMapper.valueToTree(project.get("aiCodingAccess"));
        ObjectNode aiCoding = safe.putObject("aiCodingAccess");
        aiCoding.put(
                "enabled",
                access != null
                        && access.isObject()
                        && access.path("enabled").asBoolean(false));
        return safe;
    }

    private static void addStep(
            ArrayNode steps,
            String stepKey,
            String title,
            String outcome) {
        ObjectNode step = steps.addObject();
        step.put("stepKey", stepKey);
        step.put("title", title);
        step.put("expectedOutcome", outcome);
    }

    private static void copyText(
            Map<String, Object> source,
            ObjectNode target,
            String field) {
        Object value = source.get(field);
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            target.put(field, String.valueOf(value));
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private record ProjectOnboardingReport(
            String schema,
            String summary,
            List<ProjectOnboardingStep> steps,
            List<ReportedCheck> tests,
            BrowserVerification browserVerification,
            List<String> remainingQuestions) {
    }

    private record ProjectOnboardingStep(
            String stepKey,
            String status,
            String message,
            List<String> files,
            JsonNode evidence) {
    }

}
