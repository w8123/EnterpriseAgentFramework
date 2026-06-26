package com.enterprise.ai.agent.assist;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryMapper;
import com.enterprise.ai.agent.platform.control.identity.PageCatalogRegisterResult;
import com.enterprise.ai.agent.platform.control.identity.PageRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageRegistryMapper;
import com.enterprise.ai.agent.registry.SdkAccessCheckService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiAccessSessionService {

    private static final List<StepDefinition> DEFAULT_STEPS = List.of(
            new StepDefinition("project-manifest", "读取项目接入清单"),
            new StepDefinition("backend-sdk", "接入后端 SDK 依赖"),
            new StepDefinition("reachai-config", "配置 ReachAI 注册参数"),
            new StepDefinition("capability-scan", "标注或扫描业务能力"),
            new StepDefinition("gateway-route", "配置网关路由"),
            new StepDefinition("embed-token-broker", "实现 Embed Token Broker"),
            new StepDefinition("gateway-whitelist", "配置 Embed 网关白名单"),
            new StepDefinition("frontend-embed", "接入业务前端对话入口"),
            new StepDefinition("connectivity-check", "运行平台连通性自检"),
            new StepDefinition("handoff-summary", "提交验证结果和待办")
    );

    public static final String WORKFLOW_AI_CODING_DRAFT_STEP_KEY = "workflow-ai-coding-draft";
    private static final String BROWSER_VERIFY_STEP_KEY = "browser-verify";
    private static final String BROWSER_VERIFY_STATIC_STEP_KEY = "browser-verify-static";
    private static final String BROWSER_VERIFY_RUNTIME_STEP_KEY = "browser-verify-runtime";
    private static final String WORKFLOW_AI_CODING_DRAFT_STEP_TITLE = "Workflow AI Coding 生成草稿";

    private static final List<StepDefinition> PAGE_ASSISTANT_STEPS = List.of(
            new StepDefinition("page-manifest", "读取页面助手接入清单"),
            new StepDefinition("route-detection", "确认业务前端路由"),
            new StepDefinition("page-structure", "识别页面结构"),
            new StepDefinition("action-design", "设计页面动作"),
            new StepDefinition("frontend-handler", "注册前端页面动作 handler"),
            new StepDefinition("page-registry", "同步页面动作目录"),
            new StepDefinition("browser-verify", "验证页面动作连通性"),
            new StepDefinition("handoff-summary", "提交修改清单和待办")
    );

    private static final Map<String, String> CHECK_STEP_MAPPING = Map.of(
            "project-kind", "project-manifest",
            "registry-credential", "backend-sdk",
            "online-instance", "reachai-config",
            "api-assets", "capability-scan",
            "gateway-route", "gateway-route",
            "embed-token", "embed-token-broker",
            "api-invocation", "connectivity-check"
    );

    private final ScanProjectService scanProjectService;
    private final AiAccessSessionMapper sessionMapper;
    private final AiAccessStepMapper stepMapper;
    private final SdkAccessCheckService sdkAccessCheckService;
    private final PageRegistryMapper pageRegistryMapper;
    private final PageActionRegistryMapper pageActionRegistryMapper;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AccessSessionView getOrCreateLatest(Long projectId, String toolName) {
        return getOrCreateLatest(projectId, toolName, "SDK_ACCESS", null, null, Map.of());
    }

    public AccessSessionView getOrCreatePageAssistantLatest(Long projectId, PageAssistantSessionRequest request) {
        return getOrCreateLatest(
                projectId,
                request == null ? null : request.toolName(),
                "PAGE_ASSISTANT",
                request == null ? null : request.pageKey(),
                request == null ? null : request.routePattern(),
                Map.of("actionKeys", request == null || request.actionKeys() == null ? List.of() : request.actionKeys()));
    }

    private AccessSessionView getOrCreateLatest(Long projectId,
                                                String toolName,
                                                String scenario,
                                                String targetPageKey,
                                                String targetRoute,
                                                Map<String, Object> metadata) {
        ScanProjectEntity project = scanProjectService.getById(projectId);
        List<AiAccessSessionEntity> existing = findSessions(projectId, scenario, targetPageKey);
        if (!existing.isEmpty()) {
            AiAccessSessionEntity session = existing.get(0);
            if ("PAGE_ASSISTANT".equalsIgnoreCase(normalizeScenario(scenario))) {
                if (applyTarget(session, targetPageKey, targetRoute, metadata)) {
                    sessionMapper.updateById(session);
                }
            }
            return toView(session, ensureDefaultSteps(session, loadSteps(session.getSessionId())));
        }

        LocalDateTime now = LocalDateTime.now();
        AiAccessSessionEntity session = new AiAccessSessionEntity();
        session.setSessionId("rai_" + UUID.randomUUID().toString().replace("-", ""));
        session.setProjectId(project.getId());
        session.setProjectCode(project.getProjectCode());
        session.setToolName(StringUtils.hasText(toolName) ? toolName.trim() : "AI Coding Tool");
        session.setScenario(normalizeScenario(scenario));
        session.setTargetPageKey(trimToNull(targetPageKey));
        session.setTargetRoute(trimToNull(targetRoute));
        session.setMetadataJson(writeJson(metadata == null ? Map.of() : metadata));
        session.setStatus("OPEN");
        List<StepDefinition> definitions = stepDefinitions(session);
        session.setTotalSteps(definitions.size());
        session.setCompletedSteps(0);
        session.setFailedSteps(0);
        session.setLastMessage("Access session created.");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        List<AiAccessStepEntity> steps = new ArrayList<>();
        for (StepDefinition definition : definitions) {
            AiAccessStepEntity step = newStep(project.getId(), session.getSessionId(), definition, now);
            stepMapper.insert(step);
            steps.add(step);
        }
        return toView(session, steps);
    }

    public AccessSessionView getLatest(Long projectId) {
        List<AiAccessSessionEntity> sessions = findSessions(projectId, "SDK_ACCESS", null);
        if (sessions.isEmpty()) {
            return getOrCreateLatest(projectId, null);
        }
        AiAccessSessionEntity session = sessions.get(0);
        return toView(session, ensureDefaultSteps(session, loadSteps(session.getSessionId())));
    }

    public AccessSessionView getLatestPageAssistant(Long projectId, String pageKey) {
        List<AiAccessSessionEntity> sessions = findSessions(projectId, "PAGE_ASSISTANT", pageKey);
        if (sessions.isEmpty()) {
            return getOrCreatePageAssistantLatest(projectId, new PageAssistantSessionRequest(null, pageKey, null, List.of()));
        }
        AiAccessSessionEntity session = sessions.get(0);
        return toView(session, ensureDefaultSteps(session, loadSteps(session.getSessionId())));
    }

    public List<PageAssistantSessionSummaryView> listPageAssistantSessions(Long projectId, String pageKey) {
        LambdaQueryWrapper<AiAccessSessionEntity> query = new LambdaQueryWrapper<AiAccessSessionEntity>()
                .eq(AiAccessSessionEntity::getProjectId, projectId)
                .eq(AiAccessSessionEntity::getScenario, "PAGE_ASSISTANT");
        if (StringUtils.hasText(pageKey)) {
            query.eq(AiAccessSessionEntity::getTargetPageKey, pageKey.trim());
        }
        return sessionMapper.selectList(query
                        .orderByDesc(AiAccessSessionEntity::getUpdatedAt)
                        .orderByDesc(AiAccessSessionEntity::getId)
                        .last("LIMIT 200"))
                .stream()
                .map(session -> {
                    List<AiAccessStepEntity> steps = ensureDefaultSteps(session, loadSteps(session.getSessionId()));
                    return toPageAssistantSummary(session, steps);
                })
                .toList();
    }

    public AccessSessionView reportStep(Long projectId, String sessionId, String stepKey, StepReportRequest request) {
        AiAccessSessionEntity session = requireSession(projectId, sessionId);
        List<AiAccessStepEntity> steps = ensureDefaultSteps(session, loadSteps(sessionId));
        String requestedStepKey = requireStepKey(stepKey);
        AiAccessStepEntity step = findStep(steps, stepKey);
        request = normalizeBrowserVerifyAliasReport(requestedStepKey, step, request);
        applyReport(step, request);
        applyPageAssistantTargetFromReport(session, request);
        stepMapper.updateById(step);
        return persistProgress(session, loadStepsFromKnown(steps, step));
    }

    public AccessSessionView reportWorkflowAiCodingResult(Long projectId,
                                                          String sessionId,
                                                          WorkflowAiCodingResultRequest request) {
        AiAccessSessionEntity session = requireSession(projectId, sessionId);
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(normalizeScenario(session.getScenario()))) {
            throw new IllegalArgumentException("access session is not a page assistant session");
        }
        if (request == null || !StringUtils.hasText(request.workflowId())) {
            throw new IllegalArgumentException("workflowId is required");
        }
        List<AiAccessStepEntity> steps = ensureDefaultSteps(session, loadSteps(sessionId));
        Map<String, AiAccessStepEntity> byKey = steps.stream()
                .collect(Collectors.toMap(AiAccessStepEntity::getStepKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        AiAccessStepEntity step = byKey.computeIfAbsent(WORKFLOW_AI_CODING_DRAFT_STEP_KEY, key -> {
            AiAccessStepEntity created = newStep(
                    projectId,
                    sessionId,
                    new StepDefinition(key, WORKFLOW_AI_CODING_DRAFT_STEP_TITLE),
                    LocalDateTime.now());
            stepMapper.insert(created);
            steps.add(created);
            return created;
        });
        String workflowId = request.workflowId().trim();
        String studioUrl = trimToNull(request.studioUrl());
        if (!StringUtils.hasText(studioUrl)) {
            studioUrl = "/workflows/" + workflowId + "/studio";
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("workflowId", workflowId);
        evidence.put("keySlug", valueOrEmpty(request.keySlug()));
        evidence.put("workflowName", valueOrEmpty(request.workflowName()));
        evidence.put("studioUrl", studioUrl);
        if (request.validation() != null && !request.validation().isEmpty()) {
            evidence.put("validation", request.validation());
        }
        if (request.pageAssistantValidation() != null && !request.pageAssistantValidation().isEmpty()) {
            evidence.put("pageAssistantValidation", request.pageAssistantValidation());
        }
        if (request.runtimeVerification() != null && !request.runtimeVerification().isEmpty()) {
            evidence.put("runtimeVerification", request.runtimeVerification());
        }
        String message = trimToNull(request.message());
        if (!StringUtils.hasText(message)) {
            message = "已生成 PAGE_ASSISTANT Workflow 草稿";
        }
        applyReport(step, new StepReportRequest(
                request.status(),
                message,
                List.of(),
                evidence,
                "workflow-ai-coding"));
        stepMapper.updateById(step);
        session.setLastMessage(message);
        return persistProgress(session, loadStepsFromKnown(steps, step));
    }

    public AccessSessionView resetWorkflowAiCodingResult(Long projectId, String sessionId, boolean deleteWorkflow) {
        AiAccessSessionEntity session = requireSession(projectId, sessionId);
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(normalizeScenario(session.getScenario()))) {
            throw new IllegalArgumentException("access session is not a page assistant session");
        }
        List<AiAccessStepEntity> steps = ensureDefaultSteps(session, loadSteps(sessionId));
        AiAccessStepEntity step = steps.stream()
                .filter(item -> WORKFLOW_AI_CODING_DRAFT_STEP_KEY.equals(item.getStepKey()))
                .findFirst()
                .orElse(null);
        if (step == null) {
            session.setLastMessage("Workflow AI Coding result reset.");
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
            return toView(session, steps);
        }
        Map<String, Object> evidence = readMap(step.getEvidenceJson());
        String workflowId = textValue(evidence.get("workflowId"));
        if (deleteWorkflow && StringUtils.hasText(workflowId)) {
            try {
                workflowDefinitionService.delete(workflowId.trim());
            } catch (IllegalArgumentException ex) {
                String message = ex.getMessage();
                if (message == null || !message.startsWith("workflow not found")) {
                    throw ex;
                }
            }
        }
        stepMapper.deleteById(step.getId());
        List<AiAccessStepEntity> remaining = steps.stream()
                .filter(item -> !WORKFLOW_AI_CODING_DRAFT_STEP_KEY.equals(item.getStepKey()))
                .toList();
        session.setLastMessage("Workflow AI Coding result reset.");
        return persistProgress(session, remaining);
    }

    public AccessSessionView bindPageAssistantTarget(Long projectId, String sessionId, PageAssistantTargetRequest request) {
        AiAccessSessionEntity session = requireSession(projectId, sessionId);
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(normalizeScenario(session.getScenario()))) {
            throw new IllegalArgumentException("access session is not a page assistant session");
        }
        boolean changed = applyTarget(
                session,
                request == null ? null : request.pageKey(),
                request == null ? null : request.routePattern(),
                Map.of("actionKeys", request == null || request.actionKeys() == null ? List.of() : request.actionKeys()));
        session.setLastMessage("Page assistant target bound.");
        session.setUpdatedAt(LocalDateTime.now());
        if (changed) {
            sessionMapper.updateById(session);
        }
        return toView(session, ensureDefaultSteps(session, loadSteps(sessionId)));
    }

    public PageAssistantPageRegisterSessionResult applyPageAssistantPageRegistration(
            Long projectId,
            PageAssistantPageRegisterRequest request,
            PageCatalogRegisterResult registerResult) {
        if (request == null) {
            throw new IllegalArgumentException("page assistant register request is required");
        }
        String pageKey = requiredText(request.pageKey(), "page key is required");
        String routePattern = trimToNull(request.routePattern());
        List<String> actionKeys = normalizeActionKeys(request.actions() == null
                ? List.of()
                : request.actions().stream()
                .map(PageActionCatalogContracts.PageActionDefinitionRequest::actionKey)
                .toList());
        AiAccessSessionEntity session;
        if (StringUtils.hasText(request.sessionId())) {
            session = requireSession(projectId, request.sessionId());
        } else {
            List<AiAccessSessionEntity> existing = findSessions(projectId, "PAGE_ASSISTANT", pageKey);
            if (existing.isEmpty()) {
                AccessSessionView created = getOrCreatePageAssistantLatest(projectId,
                        new PageAssistantSessionRequest(request.toolName(), pageKey, routePattern, actionKeys));
                session = requireSession(projectId, created.sessionId());
            } else {
                session = existing.get(0);
            }
        }
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(normalizeScenario(session.getScenario()))) {
            throw new IllegalArgumentException("access session is not a page assistant session");
        }
        if (StringUtils.hasText(request.toolName())) {
            session.setToolName(request.toolName().trim());
        }
        applyTarget(session, pageKey, routePattern, buildPageAssistantRegisterMetadata(request, registerResult, actionKeys));
        session.setLastMessage(StringUtils.hasText(request.handoffSummary())
                ? request.handoffSummary().trim()
                : "Page assistant registered.");
        session.setUpdatedAt(LocalDateTime.now());

        List<AiAccessStepEntity> steps = ensureDefaultSteps(session, loadSteps(session.getSessionId()));
        List<PageAssistantFileEvidence> normalizedFiles = normalizeFileEvidence(request.files());
        List<PageAssistantFileEvidenceView> enrichedFiles = enrichFileEvidence(normalizedFiles);
        List<String> files = normalizedFiles.stream().map(PageAssistantFileEvidence::path).toList();
        String fileEvidenceMessage = describeFileEvidenceQuality(enrichedFiles);
        reportKnownStep(steps, "page-manifest", "PASS",
                fileEvidenceMessage,
                files,
                Map.of(
                        "pageKey", pageKey,
                        "framework", valueOrEmpty(request.framework()),
                        "fileEvidence", enrichedFiles.stream().map(this::fileEvidenceToMap).toList()),
                "page-assistant-register");
        reportKnownStep(steps, "route-detection", StringUtils.hasText(routePattern) ? "PASS" : "WARN",
                StringUtils.hasText(routePattern) ? "Target route is registered by AI coding report." : "Route pattern is missing.",
                files,
                Map.of("routePattern", valueOrEmpty(routePattern)),
                "page-assistant-register");
        reportKnownStep(steps, "frontend-handler", verificationStatus(request.verification(), "staticHandler", actionKeys.isEmpty() ? "WARN" : "PASS"),
                verificationMessage(request.verification(), "staticHandler", actionKeys.isEmpty()
                        ? "No frontend handler action keys were reported."
                        : "Frontend handler evidence reported by AI coding tool."),
                files,
                verificationEvidence(request.verification(), "staticHandler", Map.of(
                        "bridgeGlobal", firstText(request.bridgeGlobal(), "__REACHAI_PAGE_BRIDGE__"),
                        "actionKeys", actionKeys)),
                "page-assistant-register");
        reportKnownStep(steps, "page-registry", registerResult == null || registerResult.actionCount() <= 0 ? "WARN" : "PASS",
                registerResult == null || registerResult.actionCount() <= 0
                        ? "No page actions were registered in catalog."
                        : "Page action catalog registered.",
                files,
                Map.of(
                        "pageKey", pageKey,
                        "actionCount", registerResult == null ? 0 : registerResult.actionCount(),
                        "actionKeys", actionKeys),
                "page-assistant-register");
        String browserStaticStatus = verificationStatus(request.verification(), "browserStatic",
                verificationStatus(request.verification(), "staticHandler", "WARN"));
        String browserRuntimeStatus = resolveRuntimeVerificationStatus(request.verification());
        reportKnownStep(steps, "browser-verify",
                combineBrowserVerifyStatus(browserStaticStatus, browserRuntimeStatus),
                browserVerifyMessage(request.verification(), browserStaticStatus, browserRuntimeStatus),
                files,
                browserVerifyEvidence(request.verification(), routePattern, browserStaticStatus, browserRuntimeStatus),
                "page-assistant-register");
        reportKnownStep(steps, "handoff-summary", StringUtils.hasText(request.handoffSummary()) ? "PASS" : "WARN",
                StringUtils.hasText(request.handoffSummary()) ? request.handoffSummary().trim() : "Handoff summary is missing.",
                files,
                Map.of("summary", valueOrEmpty(request.handoffSummary())),
                "page-assistant-register");
        return new PageAssistantPageRegisterSessionResult(
                persistProgress(session, steps),
                enrichedFiles);
    }

    public AccessCheckRunResponse runChecks(Long projectId,
                                            String sessionId,
                                            SdkAccessCheckService.SdkAccessCheckRequest request) {
        AiAccessSessionEntity session = requireSession(projectId, sessionId);
        List<AiAccessStepEntity> steps = ensureDefaultSteps(session, loadSteps(sessionId));
        Map<String, AiAccessStepEntity> byKey = steps.stream()
                .collect(Collectors.toMap(AiAccessStepEntity::getStepKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        SdkAccessCheckService.SdkAccessCheckResponse checkResult = sdkAccessCheckService.check(projectId, request);
        for (SdkAccessCheckService.SdkAccessCheckItem item : checkResult.checks()) {
            String mappedStepKey = CHECK_STEP_MAPPING.get(item.key());
            if (!StringUtils.hasText(mappedStepKey)) {
                continue;
            }
            AiAccessStepEntity step = byKey.computeIfAbsent(mappedStepKey, key -> {
                AiAccessStepEntity created = newStep(projectId, sessionId,
                        new StepDefinition(key, key), LocalDateTime.now());
                stepMapper.insert(created);
                steps.add(created);
                return created;
            });
            applySdkPlatformCheck(step, item);
            LocalDateTime now = LocalDateTime.now();
            if (step.getStartedAt() == null) {
                step.setStartedAt(now);
            }
            if ("PASS".equalsIgnoreCase(step.getStatus())) {
                step.setCompletedAt(now);
            }
            step.setUpdatedAt(now);
            stepMapper.updateById(step);
        }
        AccessSessionView view = persistProgress(session, steps);
        return new AccessCheckRunResponse(checkResult, view);
    }

    private void applySdkPlatformCheck(AiAccessStepEntity step, SdkAccessCheckService.SdkAccessCheckItem item) {
        Map<String, Object> platformEvidence = Map.of(
                "label", item.label(),
                "status", item.status().name(),
                "message", item.message(),
                "evidence", item.evidence() == null ? "" : item.evidence());
        if (isExternalReport(step)) {
            Map<String, Object> evidence = new LinkedHashMap<>(readMap(step.getEvidenceJson()));
            evidence.put("platformCheck", platformEvidence);
            step.setEvidenceJson(writeJson(evidence));
            return;
        }
        step.setStatus(item.status().name());
        step.setMessage(item.message());
        step.setEvidenceJson(writeJson(platformEvidence));
        step.setReportedBy("platform-check");
    }

    public PageAssistantCheckRunResponse runPageAssistantChecks(Long projectId,
                                                                String sessionId,
                                                                PageAssistantCheckRequest request) {
        AiAccessSessionEntity session = requireSession(projectId, sessionId);
        String pageKey = firstText(request == null ? null : request.pageKey(), session.getTargetPageKey());
        String routePattern = firstText(request == null ? null : request.routePattern(), session.getTargetRoute());
        List<String> actionKeys = normalizeActionKeys(request == null ? null : request.actionKeys());
        applyTarget(session, pageKey, routePattern, Map.of("actionKeys", actionKeys));
        List<AiAccessStepEntity> steps = ensureDefaultSteps(session, loadSteps(sessionId));

        ScanProjectEntity project = scanProjectService.getById(projectId);
        List<PageRegistryEntity> pages = findPages(project.getProjectCode(), pageKey);
        List<PageActionRegistryEntity> actions = findPageActions(project.getProjectCode(), pageKey);
        Map<String, PageActionRegistryEntity> actionsByKey = actions.stream()
                .collect(Collectors.toMap(PageActionRegistryEntity::getActionKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<String> expectedActions = actionKeys.isEmpty() ? new ArrayList<>(actionsByKey.keySet()) : actionKeys;

        boolean routeMatched = !pages.isEmpty() && (!StringUtils.hasText(routePattern)
                || pages.stream().anyMatch(page -> routePattern.equals(page.getRoutePattern())));
        boolean allActionsActive = !expectedActions.isEmpty()
                && expectedActions.stream().allMatch(actionsByKey::containsKey);

        boolean staticCatalogOk = routeMatched && allActionsActive;
        String staticStatus = staticCatalogOk ? "PASS" : "WARN";
        String staticMessage = staticCatalogOk
                ? "Static catalog connectivity checks passed (static only)."
                : "Static catalog alignment incomplete; verify route and action keys in the business frontend.";
        String runtimeStatus = resolveRuntimeCheckStatus(request);
        String runtimeMessage = resolveRuntimeCheckMessage(request, runtimeStatus);
        Map<String, Object> runtimeEvidence = resolveRuntimeCheckEvidence(request);

        List<PageAssistantCheckItem> checks = List.of(
                new PageAssistantCheckItem(
                        "page-manifest",
                        "Page assistant manifest",
                        StringUtils.hasText(pageKey) ? "PASS" : "WARN",
                        StringUtils.hasText(pageKey) ? "Manifest contains target pageKey." : "Target pageKey is missing.",
                        pageKey),
                new PageAssistantCheckItem(
                        "route-detection",
                        "Route detection",
                        routeMatched ? "PASS" : "WARN",
                        routeMatched ? "Target route is registered in ReachAI page catalog." : "Route is not registered yet; Cursor should verify it in the business frontend.",
                        routePattern),
                new PageAssistantCheckItem(
                        "page-registry",
                        "Page action catalog",
                        allActionsActive ? "PASS" : "WARN",
                        allActionsActive ? "Selected page actions are ACTIVE in the catalog." : "Some selected page actions are not ACTIVE in the catalog.",
                        String.join(",", expectedActions)),
                new PageAssistantCheckItem(
                        "frontend-handler",
                        "Frontend handler",
                        allActionsActive ? "PASS" : "WARN",
                        allActionsActive ? "Page action keys are ready for frontend handlers." : "Cursor still needs to register or align frontend handlers.",
                        String.join(",", actionsByKey.keySet())),
                new PageAssistantCheckItem(
                        "browser-verify-static",
                        "Browser verification (static)",
                        staticStatus,
                        staticMessage,
                        routePattern),
                new PageAssistantCheckItem(
                        "browser-verify-runtime",
                        "Browser verification (runtime)",
                        runtimeStatus,
                        runtimeMessage,
                        routePattern)
        );

        Map<String, AiAccessStepEntity> byKey = steps.stream()
                .collect(Collectors.toMap(AiAccessStepEntity::getStepKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        for (PageAssistantCheckItem item : checks) {
            if ("browser-verify-static".equals(item.key()) || "browser-verify-runtime".equals(item.key())) {
                continue;
            }
            AiAccessStepEntity step = byKey.get(item.key());
            if (step == null) {
                continue;
            }
            applyPlatformPageAssistantCheck(step, item);
            touchPlatformCheckStep(step);
            stepMapper.updateById(step);
        }
        AiAccessStepEntity browserStep = byKey.get("browser-verify");
        if (browserStep != null) {
            applyBrowserVerifyPlatformCheck(browserStep, staticStatus, staticMessage, runtimeStatus, runtimeMessage, runtimeEvidence);
            touchPlatformCheckStep(browserStep);
            stepMapper.updateById(browserStep);
        }
        AccessSessionView view = persistProgress(session, steps);
        String overall = resolvePageAssistantOverallStatus(checks);
        return new PageAssistantCheckRunResponse(
                new PageAssistantCheckResponse(projectId, project.getProjectCode(), pageKey, routePattern, overall, checks),
                view);
    }

    private List<AiAccessSessionEntity> findSessions(Long projectId, String scenario, String targetPageKey) {
        LambdaQueryWrapper<AiAccessSessionEntity> query = new LambdaQueryWrapper<AiAccessSessionEntity>()
                .eq(AiAccessSessionEntity::getProjectId, projectId)
                .eq(AiAccessSessionEntity::getScenario, normalizeScenario(scenario));
        if (StringUtils.hasText(targetPageKey)) {
            query.eq(AiAccessSessionEntity::getTargetPageKey, targetPageKey.trim());
        }
        return sessionMapper.selectList(query
                .orderByDesc(AiAccessSessionEntity::getUpdatedAt)
                .orderByDesc(AiAccessSessionEntity::getId)
                .last("LIMIT 1"));
    }

    private AiAccessSessionEntity requireSession(Long projectId, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("access session id is required");
        }
        List<AiAccessSessionEntity> sessions = sessionMapper.selectList(new LambdaQueryWrapper<AiAccessSessionEntity>()
                .eq(AiAccessSessionEntity::getProjectId, projectId)
                .eq(AiAccessSessionEntity::getSessionId, sessionId)
                .last("LIMIT 1"));
        if (sessions.isEmpty()) {
            throw new IllegalArgumentException("access session not found");
        }
        return sessions.get(0);
    }

    private List<AiAccessStepEntity> loadSteps(String sessionId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AiAccessStepEntity>()
                .eq(AiAccessStepEntity::getSessionId, sessionId)
                .orderByAsc(AiAccessStepEntity::getId));
    }

    private List<AiAccessStepEntity> ensureDefaultSteps(AiAccessSessionEntity session, List<AiAccessStepEntity> current) {
        List<StepDefinition> definitions = stepDefinitions(session);
        Map<String, AiAccessStepEntity> byKey = current.stream()
                .collect(Collectors.toMap(AiAccessStepEntity::getStepKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<AiAccessStepEntity> merged = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (StepDefinition definition : definitions) {
            AiAccessStepEntity step = byKey.get(definition.key());
            if (step == null) {
                step = newStep(session.getProjectId(), session.getSessionId(), definition, now);
                stepMapper.insert(step);
            }
            merged.add(step);
        }
        for (AiAccessStepEntity step : current) {
            if (!definitions.stream().anyMatch(definition -> definition.key().equals(step.getStepKey()))) {
                merged.add(step);
            }
        }
        return merged;
    }

    private AiAccessStepEntity newStep(Long projectId, String sessionId, StepDefinition definition, LocalDateTime now) {
        AiAccessStepEntity step = new AiAccessStepEntity();
        step.setSessionId(sessionId);
        step.setProjectId(projectId);
        step.setStepKey(definition.key());
        step.setTitle(definition.title());
        step.setStatus("TODO");
        step.setUpdatedAt(now);
        return step;
    }

    private AiAccessStepEntity findStep(List<AiAccessStepEntity> steps, String stepKey) {
        String normalized = normalizeStepKey(stepKey);
        return steps.stream()
                .filter(step -> normalized.equals(step.getStepKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown access step: " + stepKey));
    }

    private StepReportRequest normalizeBrowserVerifyAliasReport(String requestedStepKey,
                                                                AiAccessStepEntity step,
                                                                StepReportRequest request) {
        if (!BROWSER_VERIFY_STATIC_STEP_KEY.equals(requestedStepKey)
                && !BROWSER_VERIFY_RUNTIME_STEP_KEY.equals(requestedStepKey)) {
            return request;
        }
        String status = normalizeStatus(request == null ? null : request.status());
        String message = trimToNull(request == null ? null : request.message());
        Map<String, Object> evidence = new LinkedHashMap<>(readMap(step.getEvidenceJson()));
        String childKey = BROWSER_VERIFY_STATIC_STEP_KEY.equals(requestedStepKey) ? "browserStatic" : "browserRuntime";
        Map<String, Object> childEvidence = readChildEvidence(evidence.get(childKey));
        if (request != null && request.evidence() != null) {
            childEvidence.putAll(request.evidence());
        }
        childEvidence.put("status", status);
        if (StringUtils.hasText(message)) {
            childEvidence.put("message", message);
        }
        childEvidence.put("reportedStepKey", requestedStepKey);
        evidence.put(childKey, childEvidence);
        evidence.put("reportedAlias", requestedStepKey);

        String parentStatus = resolveBrowserVerifyAliasParentStatus(requestedStepKey, status, evidence);
        String parentMessage = buildBrowserVerifyAliasMessage(requestedStepKey, status, message, evidence);
        return new StepReportRequest(
                parentStatus,
                parentMessage,
                request == null ? List.of() : request.files(),
                evidence,
                request == null ? null : request.reportedBy());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readChildEvidence(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    private static String resolveBrowserVerifyAliasParentStatus(String requestedStepKey,
                                                               String status,
                                                               Map<String, Object> evidence) {
        if ("FAIL".equals(status)) {
            return "FAIL";
        }
        if (BROWSER_VERIFY_RUNTIME_STEP_KEY.equals(requestedStepKey) && "PASS".equals(status)) {
            return "PASS";
        }
        String runtimeStatus = childStatus(evidence.get("browserRuntime"));
        if ("FAIL".equals(runtimeStatus)) {
            return "FAIL";
        }
        if ("PASS".equals(runtimeStatus)) {
            return "PASS";
        }
        if ("RUNNING".equals(status)) {
            return "RUNNING";
        }
        return "WARN";
    }

    private static String buildBrowserVerifyAliasMessage(String requestedStepKey,
                                                        String status,
                                                        String message,
                                                        Map<String, Object> evidence) {
        String staticStatus = childStatus(evidence.get("browserStatic"));
        String runtimeStatus = childStatus(evidence.get("browserRuntime"));
        String suffix = StringUtils.hasText(message) ? " (" + message + ")" : "";
        if (BROWSER_VERIFY_STATIC_STEP_KEY.equals(requestedStepKey) && "PASS".equals(status)
                && !"PASS".equals(runtimeStatus)) {
            return "browser-verify-static=PASS" + suffix
                    + "; browser-verify-runtime has not reported PASS, parent step remains WARN.";
        }
        return "browser-verify-static=" + valueOrUnknown(staticStatus)
                + "; browser-verify-runtime=" + valueOrUnknown(runtimeStatus)
                + "; latest=" + requestedStepKey + "=" + status + suffix;
    }

    private static String childStatus(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object status = map.get("status");
            return status == null ? null : String.valueOf(status);
        }
        return null;
    }

    private static String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value : "UNKNOWN";
    }

    private void applyReport(AiAccessStepEntity step, StepReportRequest request) {
        LocalDateTime now = LocalDateTime.now();
        String status = normalizeStatus(request == null ? null : request.status());
        step.setStatus(status);
        step.setMessage(request == null ? null : trimToNull(request.message()));
        step.setFilesJson(writeJson(request == null || request.files() == null ? List.of() : request.files()));
        step.setEvidenceJson(writeJson(request == null || request.evidence() == null ? Map.of() : request.evidence()));
        step.setReportedBy(request == null ? null : trimToNull(request.reportedBy()));
        if (step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        if ("PASS".equals(status) || "SKIPPED".equals(status)) {
            step.setCompletedAt(now);
        }
        step.setUpdatedAt(now);
    }

    private void reportKnownStep(List<AiAccessStepEntity> steps,
                                 String stepKey,
                                 String status,
                                 String message,
                                 List<String> files,
                                 Map<String, Object> evidence,
                                 String reportedBy) {
        AiAccessStepEntity step = findStep(steps, stepKey);
        applyReport(step, new StepReportRequest(status, message, files, evidence, reportedBy));
        stepMapper.updateById(step);
    }

    private boolean applyTarget(AiAccessSessionEntity session,
                                String pageKey,
                                String routePattern,
                                Map<String, Object> metadata) {
        boolean changed = false;
        if (StringUtils.hasText(pageKey)
                && !pageKey.trim().equals(session.getTargetPageKey())) {
            session.setTargetPageKey(pageKey.trim());
            changed = true;
        }
        if (StringUtils.hasText(routePattern)
                && !routePattern.trim().equals(session.getTargetRoute())) {
            session.setTargetRoute(routePattern.trim());
            changed = true;
        }
        if (metadata != null && !metadata.isEmpty()) {
            session.setMetadataJson(writeJson(metadata));
            changed = true;
        }
        if (changed) {
            session.setUpdatedAt(LocalDateTime.now());
        }
        return changed;
    }

    private Map<String, Object> buildPageAssistantRegisterMetadata(PageAssistantPageRegisterRequest request,
                                                                   PageCatalogRegisterResult registerResult,
                                                                   List<String> actionKeys) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("actionKeys", actionKeys);
        metadata.put("pageName", valueOrEmpty(request.pageName()));
        metadata.put("framework", valueOrEmpty(request.framework()));
        metadata.put("frameworkVersion", valueOrEmpty(request.frameworkVersion()));
        metadata.put("bridgeGlobal", firstText(request.bridgeGlobal(), "__REACHAI_PAGE_BRIDGE__"));
        metadata.put("replaceActions", Boolean.TRUE.equals(request.replaceActions()));
        metadata.put("files", request.files() == null ? List.of() : request.files());
        metadata.put("verification", request.verification() == null ? Map.of() : request.verification());
        metadata.put("handoffSummary", valueOrEmpty(request.handoffSummary()));
        if (registerResult != null) {
            metadata.put("registeredPage", Map.of(
                    "projectCode", valueOrEmpty(registerResult.projectCode()),
                    "appId", valueOrEmpty(registerResult.appId()),
                    "pageKey", valueOrEmpty(registerResult.pageKey()),
                    "actionCount", registerResult.actionCount()));
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> verificationEvidence(Map<String, Object> verification,
                                                            String key,
                                                            Map<String, Object> fallback) {
        Object value = verification == null ? null : verification.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((entryKey, entryValue) -> result.put(String.valueOf(entryKey), entryValue));
            return result;
        }
        return fallback;
    }

    private static String combineBrowserVerifyStatus(String staticStatus, String runtimeStatus) {
        if ("FAIL".equalsIgnoreCase(staticStatus) || "FAIL".equalsIgnoreCase(runtimeStatus)) {
            return "FAIL";
        }
        if ("PASS".equalsIgnoreCase(staticStatus) && "PASS".equalsIgnoreCase(runtimeStatus)) {
            return "PASS";
        }
        return "WARN";
    }

    private static String browserVerifyMessage(Map<String, Object> verification,
                                               String staticStatus,
                                               String runtimeStatus) {
        String staticMessage = verificationMessage(verification, "browserStatic",
                verificationMessage(verification, "staticHandler", "Static browser verification was not reported."));
        String runtimeMessage = verificationMessage(verification, "browserRuntime",
                verificationMessage(verification, "browser", "Runtime browser verification was not reported."));
        return "static=" + staticStatus + " (" + staticMessage + "); runtime=" + runtimeStatus + " (" + runtimeMessage + ")";
    }

    private static Map<String, Object> browserVerifyEvidence(Map<String, Object> verification,
                                                             String routePattern,
                                                             String staticStatus,
                                                             String runtimeStatus) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("routePattern", valueOrEmpty(routePattern));
        evidence.put("browserStatic", verificationEvidence(verification, "browserStatic",
                verificationEvidence(verification, "staticHandler", Map.of("status", staticStatus))));
        evidence.put("browserRuntime", verificationEvidence(verification, "browserRuntime",
                verificationEvidence(verification, "browser", Map.of("status", runtimeStatus))));
        return evidence;
    }

    private static String resolveRuntimeVerificationStatus(Map<String, Object> verification) {
        Map<String, Object> runtime = extractRuntimeVerification(verification);
        if (runtime.isEmpty()) {
            return verificationStatus(verification, "browser", "WARN");
        }
        return resolveRuntimeStatusFromEvidence(runtime, verification != null && StringUtils.hasText(textValue(verification.get("frontendUrl"))));
    }

    private static Map<String, Object> extractRuntimeVerification(Map<String, Object> verification) {
        Map<String, Object> runtime = verificationEvidence(verification, "browserRuntime", Map.of());
        if (!runtime.isEmpty()) {
            return runtime;
        }
        return verificationEvidence(verification, "browser", Map.of());
    }

    public static Map<String, Object> extractRuntimeVerificationForCheck(PageAssistantCheckRequest request) {
        if (request != null && request.runtimeVerification() != null && !request.runtimeVerification().isEmpty()) {
            return new LinkedHashMap<>(request.runtimeVerification());
        }
        return Map.of();
    }

    public static String extractFrontendUrl(PageAssistantCheckRequest request, Map<String, Object> runtimeVerification) {
        if (request != null && StringUtils.hasText(request.frontendUrl())) {
            return request.frontendUrl().trim();
        }
        String fromRuntime = textValue(runtimeVerification.get("frontendUrl"));
        return StringUtils.hasText(fromRuntime) ? fromRuntime.trim() : null;
    }

    private static String resolveRuntimeCheckStatus(PageAssistantCheckRequest request) {
        Map<String, Object> runtime = extractRuntimeVerificationForCheck(request);
        if (runtime.isEmpty() && request != null && StringUtils.hasText(request.frontendUrl())) {
            return "WARN";
        }
        if (runtime.isEmpty()) {
            return "SKIPPED";
        }
        String status = resolveRuntimeStatusFromEvidence(runtime, StringUtils.hasText(extractFrontendUrl(request, runtime)));
        if ("PASS".equalsIgnoreCase(status) && hasMissingExpectedRegisteredActions(runtime, request == null ? List.of() : request.actionKeys())) {
            return "FAIL";
        }
        return status;
    }

    private static String resolveRuntimeStatusFromEvidence(Map<String, Object> runtime, boolean frontendUrlProvided) {
        String reported = textValue(runtime.get("status"));
        if (!StringUtils.hasText(reported)) {
            return frontendUrlProvided ? "WARN" : "SKIPPED";
        }
        if ("PASS".equalsIgnoreCase(reported)) {
            return hasSuccessfulBridgeInvoke(runtime) ? "PASS" : "WARN";
        }
        return reported.toUpperCase(Locale.ROOT);
    }

    private static boolean hasSuccessfulBridgeInvoke(Map<String, Object> runtime) {
        if (!objectListToStrings(runtime.get("invokedActions")).isEmpty()) {
            Object redacted = runtime.get("redactedResults");
            if (redacted instanceof List<?> results) {
                return results.stream().anyMatch(item -> {
                    if (item instanceof Map<?, ?> map) {
                        Object status = map.get("status");
                        return status != null && "SUCCESS".equalsIgnoreCase(String.valueOf(status));
                    }
                    return false;
                });
            }
            return true;
        }
        return false;
    }

    private static boolean hasMissingExpectedRegisteredActions(Map<String, Object> runtime, List<String> expectedActions) {
        List<String> expected = normalizeActionKeys(expectedActions);
        if (expected.isEmpty()) {
            return false;
        }
        List<String> registered = objectListToStrings(runtime.get("registeredActions"));
        if (registered.isEmpty()) {
            registered = objectListToStrings(runtime.get("listedActions"));
        }
        if (registered.isEmpty()) {
            registered = objectListToStrings(runtime.get("actionKeys"));
        }
        if (registered.isEmpty()) {
            return false;
        }
        List<String> normalizedRegistered = normalizeActionKeys(registered);
        return expected.stream().anyMatch(action -> !normalizedRegistered.contains(action));
    }

    private static Map<String, Object> resolveRuntimeCheckEvidence(PageAssistantCheckRequest request) {
        Map<String, Object> runtime = extractRuntimeVerificationForCheck(request);
        if (!runtime.isEmpty()) {
            return runtime;
        }
        if (request != null && StringUtils.hasText(request.frontendUrl())) {
            return Map.of(
                    "status", "WARN",
                    "message", "FrontendUrl was provided but runtime bridge invoke evidence is missing.",
                    "frontendUrl", request.frontendUrl().trim());
        }
        return Map.of(
                "status", "SKIPPED",
                "message", "Runtime browser verification skipped: no FrontendUrl, browser session, login state, or bridge invoke probe.");
    }

    private static String resolveRuntimeCheckMessage(PageAssistantCheckRequest request, String runtimeStatus) {
        Map<String, Object> runtime = extractRuntimeVerificationForCheck(request);
        if ("PASS".equalsIgnoreCase(runtimeStatus)) {
            return readRuntimeVerificationMessage(runtime, "Runtime bridge invoke verification reported PASS.");
        }
        if ("FAIL".equalsIgnoreCase(runtimeStatus)) {
            return readRuntimeVerificationMessage(runtime, "Runtime bridge invoke verification failed.");
        }
        if ("WARN".equalsIgnoreCase(runtimeStatus)) {
            if (runtime.isEmpty() && request != null && StringUtils.hasText(request.frontendUrl())) {
                return "FrontendUrl was provided but authenticated browser bridge invoke was not confirmed.";
            }
            return readRuntimeVerificationMessage(runtime, "Runtime browser verification reported WARN.");
        }
        return readRuntimeVerificationMessage(runtime,
                "Runtime browser verification skipped: no FrontendUrl, browser session, login state, or bridge invoke probe.");
    }

    private static String readRuntimeVerificationMessage(Map<String, Object> runtimeVerification, String fallback) {
        if (runtimeVerification == null || runtimeVerification.isEmpty()) {
            return fallback;
        }
        Object message = runtimeVerification.get("message");
        return message != null && StringUtils.hasText(String.valueOf(message)) ? String.valueOf(message) : fallback;
    }

    public static List<PageAssistantFileEvidenceView> enrichFileEvidence(List<PageAssistantFileEvidence> files) {
        return normalizeFileEvidence(files).stream()
                .map(AiAccessSessionService::toFileEvidenceView)
                .toList();
    }

    public static String describeFileEvidenceQuality(List<PageAssistantFileEvidenceView> files) {
        if (files == null || files.isEmpty()) {
            return "No file evidence was reported.";
        }
        long hashMissing = files.stream().filter(file -> "HASH_MISSING".equalsIgnoreCase(file.validationStatus())).count();
        if (hashMissing > 0) {
            return "File evidence recorded for " + files.size() + " path(s); "
                    + hashMissing + " path(s) are path-only without sha256 (hash missing / local verify recommended).";
        }
        return "File evidence recorded for " + files.size() + " path(s) with sha256 verification.";
    }

    private static PageAssistantFileEvidenceView toFileEvidenceView(PageAssistantFileEvidence file) {
        boolean exists = file.exists() == null || Boolean.TRUE.equals(file.exists());
        boolean hashVerified = StringUtils.hasText(file.sha256());
        String validationStatus = hashVerified ? "VERIFIED" : "HASH_MISSING";
        String validationMessage = hashVerified
                ? "Local file hash captured."
                : "Path-only evidence without sha256; run helper verify for hash missing / local verify recommended.";
        return new PageAssistantFileEvidenceView(
                file.path(),
                file.role(),
                exists,
                file.sha256(),
                validationStatus,
                validationMessage);
    }

    private Map<String, Object> fileEvidenceToMap(PageAssistantFileEvidenceView file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", file.path());
        map.put("role", file.role());
        map.put("exists", file.exists());
        map.put("sha256", file.sha256());
        map.put("validationStatus", file.validationStatus());
        map.put("validationMessage", file.validationMessage());
        return map;
    }

    public static List<PageAssistantFileEvidence> normalizeFileEvidence(List<PageAssistantFileEvidence> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream()
                .filter(file -> file != null && StringUtils.hasText(file.path()))
                .map(file -> new PageAssistantFileEvidence(
                        file.path().trim(),
                        StringUtils.hasText(file.role()) ? file.role().trim() : "unknown",
                        file.exists(),
                        StringUtils.hasText(file.sha256()) ? file.sha256().trim() : null))
                .toList();
    }

    private void applyBrowserVerifyPlatformCheck(AiAccessStepEntity step,
                                                 String staticStatus,
                                                 String staticMessage,
                                                 String runtimeStatus,
                                                 String runtimeMessage,
                                                 Map<String, Object> runtimeEvidence) {
        Map<String, Object> platformEvidence = new LinkedHashMap<>();
        platformEvidence.put("browserStatic", Map.of("status", staticStatus, "message", staticMessage));
        Map<String, Object> browserRuntime = new LinkedHashMap<>(runtimeEvidence == null ? Map.of() : runtimeEvidence);
        if (!browserRuntime.containsKey("status")) {
            browserRuntime.put("status", runtimeStatus);
        }
        if (!browserRuntime.containsKey("message")) {
            browserRuntime.put("message", runtimeMessage);
        }
        platformEvidence.put("browserRuntime", browserRuntime);
        mergePlatformCheck(step, platformEvidence, combineBrowserVerifyStatus(staticStatus, runtimeStatus),
                "static=" + staticStatus + " (static only); runtime=" + runtimeStatus);
    }

    private void mergePlatformCheck(AiAccessStepEntity step,
                                    Map<String, Object> platformEvidence,
                                    String platformStatus,
                                    String platformMessage) {
        if (isExternalReport(step)) {
            Map<String, Object> evidence = new LinkedHashMap<>(readMap(step.getEvidenceJson()));
            evidence.put("platformCheck", platformEvidence);
            step.setEvidenceJson(writeJson(evidence));
            return;
        }
        step.setStatus(platformStatus);
        step.setMessage(platformMessage);
        step.setEvidenceJson(writeJson(platformEvidence));
        step.setReportedBy("platform-page-assistant-check");
    }

    private static boolean isExternalReport(AiAccessStepEntity step) {
        return StringUtils.hasText(step.getReportedBy())
                && !"platform-page-assistant-check".equalsIgnoreCase(step.getReportedBy())
                && !"platform-check".equalsIgnoreCase(step.getReportedBy());
    }

    private static String verificationStatus(Map<String, Object> verification, String key, String fallback) {
        Object value = verificationEvidence(verification, key, Map.of()).get("status");
        if (!StringUtils.hasText(value == null ? null : String.valueOf(value))) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static String verificationMessage(Map<String, Object> verification, String key, String fallback) {
        Object value = verificationEvidence(verification, key, Map.of()).get("message");
        if (!StringUtils.hasText(value == null ? null : String.valueOf(value))) {
            Object command = verificationEvidence(verification, key, Map.of()).get("command");
            if (StringUtils.hasText(command == null ? null : String.valueOf(command))) {
                return String.valueOf(command);
            }
            return fallback;
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private void applyPageAssistantTargetFromReport(AiAccessSessionEntity session, StepReportRequest request) {
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(normalizeScenario(session.getScenario()))
                || request == null
                || request.evidence() == null
                || request.evidence().isEmpty()) {
            return;
        }
        Map<String, Object> evidence = request.evidence();
        Object target = evidence.get("target");
        if (target instanceof Map<?, ?> targetMap) {
            evidence = (Map<String, Object>) targetMap;
        }
        String pageKey = firstText(textValue(evidence.get("pageKey")), textValue(evidence.get("targetPageKey")));
        String route = firstText(textValue(evidence.get("routePattern")), textValue(evidence.get("route")));
        List<String> actionKeys = objectListToStrings(evidence.get("actionKeys"));
        applyTarget(session, pageKey, route, actionKeys.isEmpty() ? Map.of() : Map.of("actionKeys", actionKeys));
    }

    private static String resolvePageAssistantOverallStatus(List<PageAssistantCheckItem> checks) {
        if (checks.stream().anyMatch(check -> "FAIL".equalsIgnoreCase(check.status()))) {
            return "FAIL";
        }
        if (checks.stream().anyMatch(check -> "WARN".equalsIgnoreCase(check.status())
                || "SKIPPED".equalsIgnoreCase(check.status()))) {
            return "WARN";
        }
        return "PASS";
    }

    private void touchPlatformCheckStep(AiAccessStepEntity step) {
        LocalDateTime now = LocalDateTime.now();
        if (step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        if (isCompleted(step.getStatus())) {
            step.setCompletedAt(now);
        }
        step.setUpdatedAt(now);
    }

    private void applyPlatformPageAssistantCheck(AiAccessStepEntity step, PageAssistantCheckItem item) {
        Map<String, Object> platformEvidence = Map.of(
                "label", item.label(),
                "status", item.status(),
                "message", item.message(),
                "evidence", item.evidence() == null ? "" : item.evidence());
        mergePlatformCheck(step, platformEvidence, item.status(), item.message());
    }

    private AccessSessionView persistProgress(AiAccessSessionEntity session, List<AiAccessStepEntity> steps) {
        int total = steps.size();
        int completed = (int) steps.stream().filter(step -> isCompleted(step.getStatus())).count();
        int failed = (int) steps.stream().filter(step -> "FAIL".equalsIgnoreCase(step.getStatus())).count();
        String status = resolveOverallStatus(session, steps);
        session.setStatus(status);
        session.setTotalSteps(total);
        session.setCompletedSteps(completed);
        session.setFailedSteps(failed);
        session.setLastMessage(steps.stream()
                .filter(step -> StringUtils.hasText(step.getMessage()))
                .reduce((first, second) -> second)
                .map(AiAccessStepEntity::getMessage)
                .orElse(session.getLastMessage()));
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        return toView(session, steps);
    }

    private List<AiAccessStepEntity> loadStepsFromKnown(List<AiAccessStepEntity> steps, AiAccessStepEntity updated) {
        return steps.stream()
                .map(step -> step.getStepKey().equals(updated.getStepKey()) ? updated : step)
                .toList();
    }

    private static String resolveOverallStatus(AiAccessSessionEntity session, List<AiAccessStepEntity> steps) {
        if (steps.stream().anyMatch(step -> "FAIL".equalsIgnoreCase(step.getStatus()))) {
            return "FAIL";
        }
        if (steps.stream().anyMatch(step -> "WARN".equalsIgnoreCase(step.getStatus()))) {
            return "WARN";
        }
        String terminalStepKey = "PAGE_ASSISTANT".equalsIgnoreCase(normalizeScenario(session.getScenario()))
                ? "browser-verify"
                : "handoff-summary";
        boolean terminalDone = steps.stream()
                .anyMatch(step -> terminalStepKey.equals(step.getStepKey()) && isCompleted(step.getStatus()));
        if (terminalDone) {
            return "PASS";
        }
        if (steps.stream().anyMatch(step -> !"TODO".equalsIgnoreCase(step.getStatus()))) {
            return "RUNNING";
        }
        return "OPEN";
    }

    private AccessSessionView toView(AiAccessSessionEntity session, List<AiAccessStepEntity> steps) {
        return new AccessSessionView(
                session.getSessionId(),
                session.getProjectId(),
                session.getProjectCode(),
                session.getToolName(),
                normalizeScenario(session.getScenario()),
                session.getTargetPageKey(),
                session.getTargetRoute(),
                session.getStatus(),
                valueOrZero(session.getTotalSteps(), steps.size()),
                valueOrZero(session.getCompletedSteps(), (int) steps.stream().filter(step -> isCompleted(step.getStatus())).count()),
                valueOrZero(session.getFailedSteps(), (int) steps.stream().filter(step -> "FAIL".equalsIgnoreCase(step.getStatus())).count()),
                session.getLastMessage(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                steps.stream().map(this::toStepView).toList());
    }

    private AccessStepView toStepView(AiAccessStepEntity step) {
        return new AccessStepView(
                step.getStepKey(),
                step.getTitle(),
                step.getStatus(),
                step.getMessage(),
                readList(step.getFilesJson()),
                readMap(step.getEvidenceJson()),
                step.getReportedBy(),
                step.getStartedAt(),
                step.getCompletedAt(),
                step.getUpdatedAt());
    }

    private PageAssistantSessionSummaryView toPageAssistantSummary(AiAccessSessionEntity session, List<AiAccessStepEntity> steps) {
        Map<String, Object> metadata = readMap(session.getMetadataJson());
        int actionCount = objectListToStrings(metadata.get("actionKeys")).size();
        LocalDateTime lastReportedAt = steps.stream()
                .map(AiAccessStepEntity::getUpdatedAt)
                .filter(value -> value != null)
                .max(LocalDateTime::compareTo)
                .orElse(session.getUpdatedAt());
        return new PageAssistantSessionSummaryView(
                session.getSessionId(),
                session.getProjectId(),
                session.getProjectCode(),
                session.getToolName(),
                session.getTargetPageKey(),
                session.getTargetRoute(),
                session.getStatus(),
                resolvePageAssistantCompletionState(session, steps),
                valueOrZero(session.getTotalSteps(), steps.size()),
                valueOrZero(session.getCompletedSteps(), (int) steps.stream().filter(step -> isCompleted(step.getStatus())).count()),
                valueOrZero(session.getFailedSteps(), (int) steps.stream().filter(step -> "FAIL".equalsIgnoreCase(step.getStatus())).count()),
                actionCount,
                session.getLastMessage(),
                lastReportedAt,
                steps.stream().map(this::toStepView).toList());
    }

    private static String resolvePageAssistantCompletionState(AiAccessSessionEntity session, List<AiAccessStepEntity> steps) {
        if (!StringUtils.hasText(session.getTargetPageKey())) {
            return "WAITING_TARGET";
        }
        if ("PASS".equalsIgnoreCase(session.getStatus())
                || steps.stream().anyMatch(step -> "browser-verify".equals(step.getStepKey()) && isCompleted(step.getStatus()))) {
            return "COMPLETED";
        }
        if ("FAIL".equalsIgnoreCase(session.getStatus())
                || steps.stream().anyMatch(step -> "FAIL".equalsIgnoreCase(step.getStatus()))) {
            return "BLOCKED";
        }
        if (steps.stream().anyMatch(step -> !"TODO".equalsIgnoreCase(step.getStatus()))) {
            return "IN_PROGRESS";
        }
        return "WAITING_TARGET";
    }

    private List<String> readList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private List<PageRegistryEntity> findPages(String projectCode, String pageKey) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(pageKey)) {
            return List.of();
        }
        return pageRegistryMapper.selectList(new LambdaQueryWrapper<PageRegistryEntity>()
                .eq(PageRegistryEntity::getProjectCode, projectCode)
                .eq(PageRegistryEntity::getPageKey, pageKey.trim())
                .eq(PageRegistryEntity::getStatus, "ACTIVE"));
    }

    private List<PageActionRegistryEntity> findPageActions(String projectCode, String pageKey) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(pageKey)) {
            return List.of();
        }
        return pageActionRegistryMapper.selectList(new LambdaQueryWrapper<PageActionRegistryEntity>()
                .eq(PageActionRegistryEntity::getProjectCode, projectCode)
                .eq(PageActionRegistryEntity::getPageKey, pageKey.trim())
                .eq(PageActionRegistryEntity::getStatus, "ACTIVE"));
    }

    private static boolean isCompleted(String status) {
        return "PASS".equalsIgnoreCase(status) || "SKIPPED".equalsIgnoreCase(status);
    }

    private static List<StepDefinition> stepDefinitions(AiAccessSessionEntity session) {
        return "PAGE_ASSISTANT".equalsIgnoreCase(normalizeScenario(session.getScenario()))
                ? PAGE_ASSISTANT_STEPS
                : DEFAULT_STEPS;
    }

    private static int valueOrZero(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String normalizeStepKey(String value) {
        String normalized = requireStepKey(value);
        if (BROWSER_VERIFY_STATIC_STEP_KEY.equals(normalized)
                || BROWSER_VERIFY_RUNTIME_STEP_KEY.equals(normalized)) {
            return BROWSER_VERIFY_STEP_KEY;
        }
        return normalized;
    }

    private static String requireStepKey(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("access step key is required");
        }
        return value.trim();
    }

    private static String normalizeStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return "RUNNING";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TODO", "RUNNING", "PASS", "WARN", "FAIL", "SKIPPED" -> normalized;
            case "SUCCESS", "DONE", "COMPLETED" -> "PASS";
            case "ERROR" -> "FAIL";
            default -> throw new IllegalArgumentException("unsupported access step status: " + value);
        };
    }

    private static String normalizeScenario(String value) {
        if (!StringUtils.hasText(value)) {
            return "SDK_ACCESS";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "PAGE_ASSISTANT".equals(normalized) ? "PAGE_ASSISTANT" : "SDK_ACCESS";
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String requiredText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : trimToNull(second);
    }

    private static String valueOrEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static String textValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> objectListToStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static List<String> normalizeActionKeys(List<String> actionKeys) {
        if (actionKeys == null) {
            return List.of();
        }
        return actionKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private record StepDefinition(String key, String title) {
    }

    public record WorkflowAiCodingResultRequest(
            String workflowId,
            String keySlug,
            String workflowName,
            String status,
            String message,
            Map<String, Object> validation,
            Map<String, Object> pageAssistantValidation,
            Map<String, Object> runtimeVerification,
            String studioUrl
    ) {
    }

    public record StepReportRequest(
            String status,
            String message,
            List<String> files,
            Map<String, Object> evidence,
            String reportedBy
    ) {
    }

    public record AccessCheckRunResponse(
            SdkAccessCheckService.SdkAccessCheckResponse checkResult,
            AccessSessionView session
    ) {
    }

    public record AccessSessionView(
            String sessionId,
            Long projectId,
            String projectCode,
            String toolName,
            String scenario,
            String targetPageKey,
            String targetRoute,
            String status,
            int totalSteps,
            int completedSteps,
            int failedSteps,
            String lastMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<AccessStepView> steps
    ) {
    }

    public record AccessStepView(
            String stepKey,
            String title,
            String status,
            String message,
            List<String> files,
            Map<String, Object> evidence,
            String reportedBy,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            LocalDateTime updatedAt
    ) {
    }

    public record PageAssistantSessionSummaryView(
            String sessionId,
            Long projectId,
            String projectCode,
            String toolName,
            String targetPageKey,
            String targetRoute,
            String status,
            String completionState,
            int totalSteps,
            int completedSteps,
            int failedSteps,
            int actionCount,
            String lastMessage,
            LocalDateTime lastReportedAt,
            List<AccessStepView> steps
    ) {
    }

    public record PageAssistantSessionRequest(
            String toolName,
            String pageKey,
            String routePattern,
            List<String> actionKeys
    ) {
    }

    public record PageAssistantTargetRequest(
            String pageKey,
            String routePattern,
            List<String> actionKeys
    ) {
    }

    public record PageAssistantCheckRequest(
            String pageKey,
            String routePattern,
            List<String> actionKeys,
            String frontendUrl,
            Map<String, Object> runtimeVerification
    ) {
    }

    public record PageAssistantFileEvidence(
            String path,
            String role,
            Boolean exists,
            String sha256
    ) {
    }

    public record PageAssistantPageRegisterRequest(
            String sessionId,
            String toolName,
            String pageKey,
            String pageName,
            String routePattern,
            String framework,
            String frameworkVersion,
            String bridgeGlobal,
            Boolean replaceActions,
            @JsonDeserialize(using = PageAssistantFileEvidenceListDeserializer.class)
            List<PageAssistantFileEvidence> files,
            List<PageActionCatalogContracts.PageActionDefinitionRequest> actions,
            Map<String, Object> verification,
            String handoffSummary
    ) {
    }

    public static PageAssistantCheckRequest buildCheckRequestFromRegister(PageAssistantPageRegisterRequest request,
                                                                          List<String> actionKeys) {
        Map<String, Object> runtime = extractRuntimeVerification(request == null ? null : request.verification());
        return new PageAssistantCheckRequest(
                request == null ? null : request.pageKey(),
                request == null ? null : request.routePattern(),
                actionKeys,
                extractFrontendUrl(new PageAssistantCheckRequest(null, null, null, null, runtime), runtime),
                runtime.isEmpty() ? null : runtime);
    }

    public record PageAssistantFileEvidenceView(
            String path,
            String role,
            Boolean exists,
            String sha256,
            String validationStatus,
            String validationMessage
    ) {
    }

    public record PageAssistantPageRegisterSessionResult(
            AccessSessionView session,
            List<PageAssistantFileEvidenceView> fileEvidence
    ) {
    }

    public record PageAssistantCheckRunResponse(
            PageAssistantCheckResponse checkResult,
            AccessSessionView session
    ) {
    }

    public record PageAssistantCheckResponse(
            Long projectId,
            String projectCode,
            String pageKey,
            String routePattern,
            String overallStatus,
            List<PageAssistantCheckItem> checks
    ) {
    }

    public record PageAssistantCheckItem(
            String key,
            String label,
            String status,
            String message,
            String evidence
    ) {
    }
}
