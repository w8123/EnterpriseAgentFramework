package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageIntegrationReadinessItem;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageIntegrationReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.platform.PlatformEmbedSessionEntity;
import com.enterprise.ai.control.platform.PlatformEmbedSessionMapper;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService;
import com.enterprise.ai.control.platform.PlatformPageActionEventEntity;
import com.enterprise.ai.control.platform.PlatformPageActionEventMapper;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only, server-observed Page Bridge/Catalog/Action integration check.
 *
 * <p>This view is diagnostic. It does not mutate catalog state and is not a
 * release gate while a project is still establishing its first end-to-end
 * integration.</p>
 */
@Service
@RequiredArgsConstructor
public class PageWorkbenchPageReadinessApplicationService {

    public static final String SCHEMA =
            "reachai.page-workbench.page-integration-readiness.v1";

    private final PageCatalogApplicationService pageCatalog;
    private final PlatformEmbedSessionMapper sessionMapper;
    private final PlatformPageActionEventMapper actionEventMapper;
    private final PlatformEmbedE2eEvidenceService embedEvidence;
    private final PageWorkbenchPublishedApplicationService publishedService;
    private final PageWorkbenchAgentModelReadinessApplicationService modelReadiness;
    private final PageWorkbenchWorkflowTraceReadinessApplicationService
            workflowTraceReadiness;
    private final ObjectMapper objectMapper;

    public PageIntegrationReadinessView evaluate(
            String projectCode,
            Long pageId) {
        String targetProjectCode = requireText(projectCode, "projectCode");
        PageView page = pageCatalog.findPage(targetProjectCode, pageId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "page not found: " + pageId));
        LocalDateTime now = LocalDateTime.now();
        List<PageIntegrationReadinessItem> items = new ArrayList<>();

        boolean definitionReady = StringUtils.hasText(page.routePattern())
                || StringUtils.hasText(page.componentPath());
        items.add(item(
                "PAGE_DEFINITION_READY",
                "页面信息",
                definitionReady ? "PASS" : "PENDING",
                definitionReady
                        ? "页面已具备可定位的路由或入口组件。"
                        : "请补充页面路由或入口组件，避免接入目标含糊。",
                evidence()
                        .put("routePattern", safe(page.routePattern()))
                        .put("componentPath", safe(page.componentPath()))));

        boolean businessPageUrlReady = StringUtils.hasText(page.businessPageUrl());
        items.add(item(
                "BUSINESS_PAGE_URL_READY",
                "业务页面入口",
                businessPageUrlReady ? "PASS" : "PENDING",
                businessPageUrlReady
                        ? "已配置浏览器可直接打开的业务页面地址。"
                        : "请在页面信息中配置浏览器可直接打开的绝对 HTTP(S) 业务页面地址；项目 Base URL 仅用于后端 API，不能代替页面入口。",
                evidence().put("businessPageUrl", safe(page.businessPageUrl()))));

        Set<String> expectedActions = page.actions().stream()
                .filter(action -> "ACTIVE".equalsIgnoreCase(action.status()))
                .map(PageWorkbenchContract.ActionView::actionKey)
                .filter(StringUtils::hasText)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll);
        items.add(item(
                "PAGE_ACTION_CATALOG_READY",
                "页面操作目录",
                expectedActions.isEmpty() ? "PENDING" : "PASS",
                expectedActions.isEmpty()
                        ? "当前页面尚未声明可供 Workflow 使用的页面操作。"
                        : "页面操作目录中已有 "
                        + expectedActions.size() + " 个有效操作。",
                evidence()
                        .put("activeActionCount", expectedActions.size())
                        .set(
                                "actionKeys",
                                objectMapper.valueToTree(expectedActions))));

        PlatformEmbedSessionEntity session = latestActiveSession(
                targetProjectCode,
                page.pageKey(),
                now);
        boolean sessionReady = session != null
                && StringUtils.hasText(session.getSdkVersion())
                && StringUtils.hasText(session.getPageInstanceId());
        items.add(item(
                "PAGE_BRIDGE_SESSION_READY",
                "Page Bridge 会话",
                sessionReady ? "PASS" : "PENDING",
                sessionReady
                        ? "ReachAI 已观察到当前页面的有效 SDK 会话。"
                        : "尚未观察到当前页面携带 SDK 版本和 pageInstanceId 的有效会话。",
                sessionEvidence(session)));

        LocalDateTime observedAfter = observedAfter(session);
        items.add(browserConversationReadiness(
                targetProjectCode,
                page.pageKey(),
                sessionReady,
                observedAfter,
                session));

        Set<String> observedActions = session == null
                ? Set.of()
                : readBridgeActions(session.getBridgeActionsJson());
        Set<String> missingActions = new LinkedHashSet<>(expectedActions);
        missingActions.removeAll(observedActions);
        String bindingStatus;
        String bindingMessage;
        if (expectedActions.isEmpty() || !sessionReady) {
            bindingStatus = "PENDING";
            bindingMessage = "需要先完成操作声明和真实页面会话，才能核对 Bridge 注册。";
        } else if (missingActions.isEmpty()) {
            bindingStatus = "PASS";
            bindingMessage = "当前页面会话已注册目录要求的全部页面操作。";
        } else {
            bindingStatus = "FAIL";
            bindingMessage = "当前页面会话缺少 "
                    + missingActions.size() + " 个已声明操作。";
        }
        ObjectNode bindingEvidence = evidence()
                .put("expectedActionCount", expectedActions.size())
                .put("observedActionCount", observedActions.size());
        bindingEvidence.set(
                "missingActionKeys",
                objectMapper.valueToTree(missingActions));
        items.add(item(
                "PAGE_ACTION_BINDING_READY",
                "Bridge 操作注册",
                bindingStatus,
                bindingMessage,
                bindingEvidence));

        PlatformPageActionEventEntity latestEvent = session == null
                ? null
                : latestActionEvent(session.getSessionId(), page.pageKey());
        String runtimeStatus;
        String runtimeMessage;
        if (latestEvent == null) {
            runtimeStatus = "PENDING";
            runtimeMessage = "尚未观察到当前页面操作的真实执行结果。";
        } else if ("SUCCESS".equalsIgnoreCase(latestEvent.getStatus())) {
            runtimeStatus = "PASS";
            runtimeMessage = "最近一次页面操作已由业务页面成功回传结果。";
        } else if (isBusinessTerminalStatus(latestEvent.getStatus())) {
            runtimeStatus = "PASS";
            runtimeMessage = "最近一次页面操作已完成并返回业务终态："
                    + latestEvent.getStatus() + "。这不是 ReachAI 或页面桥的技术故障。";
        } else {
            runtimeStatus = "WARN";
            runtimeMessage = "最近一次页面操作已回传，但状态为 "
                    + latestEvent.getStatus() + "。";
        }
        items.add(item(
                "PAGE_ACTION_RUNTIME_READY",
                "页面操作执行",
                runtimeStatus,
                runtimeMessage,
                actionEventEvidence(latestEvent)));

        PublishedWorkflowView published = null;
        PageIntegrationReadinessItem workflowRelease = null;
        try {
            published = latestPublished(
                    publishedService.list(targetProjectCode, page.pageKey()));
        } catch (RuntimeException ex) {
            workflowRelease = item(
                    "PAGE_WORKFLOW_RELEASE_READY",
                    "Workflow 发布与 Agent 挂载",
                    "WARN",
                    "Runtime 当前不可用，无法确认当前页面是否已有已发布并挂载的 PAGE_ASSISTANT Workflow。",
                    evidence().put("source", "reachai-runtime-service"));
        }
        if (workflowRelease == null) {
            workflowRelease = workflowReleaseReadiness(published);
        }
        items.add(workflowRelease);
        items.add(modelReadiness(published));

        PageIntegrationReadinessItem workflowTrace = workflowTraceReadiness(
                targetProjectCode,
                page.pageKey(),
                sessionReady,
                observedAfter,
                published);
        items.add(workflowTrace);
        items.add(nodeExecutionReadiness(
                "CAPABILITY_TOOL_E2E_READY",
                "业务能力 / API 调用",
                "capabilityRequired",
                "capabilityObserved",
                null,
                workflowTrace,
                "当前发布的 Workflow 未声明 TOOL 节点，本页不要求业务能力 API 验收。",
                "Workflow 已声明业务能力 / API 调用，但本次真实 Trace 尚未观察到其成功执行。"));
        items.add(nodeExecutionReadiness(
                "PAGE_ACTION_E2E_READY",
                "Workflow 页面操作",
                "pageActionRequired",
                "pageActionObserved",
                "pageActionBusinessTerminalObserved",
                workflowTrace,
                "当前发布的 Workflow 未声明 PAGE_ACTION 节点，本页不要求 Workflow 页面操作验收。",
                "Workflow 已声明 PAGE_ACTION 节点，但本次真实 Trace 尚未观察到其成功执行。"));
        items.add(writeActionReadiness(
                page,
                session,
                sessionReady));

        String status = summaryStatus(items);
        String message = switch (status) {
            case "PASS" -> "页面接入的目录、Bridge 注册和真实操作执行均已观察到。";
            case "FAIL" -> "页面接入存在明确不一致，请按检查项修正后重试。";
            default -> "页面接入尚未形成完整观测链路，可继续按检查项推进。";
        };
        return new PageIntegrationReadinessView(
                SCHEMA,
                targetProjectCode,
                page.id(),
                page.pageKey(),
                status,
                message,
                List.copyOf(items),
                now);
    }

    private PlatformEmbedSessionEntity latestActiveSession(
            String projectCode,
            String pageKey,
            LocalDateTime now) {
        PlatformEmbedSessionEntity session = sessionMapper.selectOne(
                Wrappers.<PlatformEmbedSessionEntity>lambdaQuery()
                        .eq(
                                PlatformEmbedSessionEntity::getProjectCode,
                                projectCode)
                        .eq(PlatformEmbedSessionEntity::getPageKey, pageKey)
                        .eq(PlatformEmbedSessionEntity::getStatus, "ACTIVE")
                        .orderByDesc(
                                PlatformEmbedSessionEntity::getUpdatedAt)
                        .last("LIMIT 1"));
        if (session == null
                || (session.getExpiresAt() != null
                && !session.getExpiresAt().isAfter(now))) {
            return null;
        }
        return session;
    }

    private PlatformPageActionEventEntity latestActionEvent(
            String sessionId,
            String pageKey) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        return actionEventMapper.selectOne(
                Wrappers.<PlatformPageActionEventEntity>lambdaQuery()
                        .eq(
                                PlatformPageActionEventEntity::getSessionId,
                                sessionId)
                        .eq(
                                PlatformPageActionEventEntity::getCommandType,
                                "PAGE_ACTION")
                        .eq(
                                PlatformPageActionEventEntity::getTargetPageKey,
                                pageKey)
                        .in(
                                PlatformPageActionEventEntity::getStatus,
                                List.of(
                                        "SUCCESS",
                                        "NO_DATA",
                                        "PRECONDITION_FAILED",
                                        "USER_CANCELLED",
                                        "FAILED",
                                        "CANCELLED",
                                        "ACTION_NOT_FOUND",
                                        "FORBIDDEN",
                                        "TIMEOUT"))
                        .orderByDesc(
                                PlatformPageActionEventEntity::getCompletedAt)
                        .last("LIMIT 1"));
    }

    private PageIntegrationReadinessItem browserConversationReadiness(
            String projectCode,
            String pageKey,
            boolean sessionReady,
            LocalDateTime observedAfter,
            PlatformEmbedSessionEntity session) {
        if (!sessionReady) {
            return item(
                    PageWorkbenchBrowserReadinessApplicationService.KEY,
                    "浏览器真实对话",
                    "PENDING",
                    "需要先建立带 SDK 版本和 pageInstanceId 的真实页面会话，才能验证用户消息与助手回复。",
                    sessionEvidence(session));
        }
        if (observedAfter == null) {
            return item(
                    PageWorkbenchBrowserReadinessApplicationService.KEY,
                    "浏览器真实对话",
                    "PENDING",
                    "当前页面会话缺少可用的开始时间，无法界定本次真实浏览器对话的观测窗口。",
                    sessionEvidence(session));
        }
        ObjectNode evidence = sessionEvidence(session);
        evidence.put("observedAfter", observedAfter.toString());
        PlatformEmbedE2eEvidenceService.EmbedConversationEvidence observed;
        try {
            observed = embedEvidence.latestSuccessfulConversation(
                    projectCode,
                    pageKey,
                    observedAfter);
        } catch (DataAccessException ex) {
            evidence.put("dependency", "reachai-control-database");
            evidence.put("reason", "DATA_ACCESS_UNAVAILABLE");
            return item(
                    PageWorkbenchBrowserReadinessApplicationService.KEY,
                    "浏览器真实对话",
                    "PENDING",
                    "ReachAI 会话证据暂时不可用，尚未完成真实浏览器对话校验。请恢复平台数据库连接后重新验证。",
                    evidence);
        }
        if (StringUtils.hasText(observed.evidence())) {
            evidence.put("observation", observed.evidence());
        }
        return item(
                PageWorkbenchBrowserReadinessApplicationService.KEY,
                "浏览器真实对话",
                observed.passed() ? "PASS" : "PENDING",
                observed.message(),
                evidence);
    }

    private PageIntegrationReadinessItem workflowReleaseReadiness(
            PublishedWorkflowView published) {
        if (published == null) {
            return item(
                    "PAGE_WORKFLOW_RELEASE_READY",
                    "Workflow 发布与 Agent 挂载",
                    "PENDING",
                    "当前页面尚未观察到已发布且已挂载到 Agent 的 PAGE_ASSISTANT Workflow。",
                    evidence());
        }
        return item(
                "PAGE_WORKFLOW_RELEASE_READY",
                "Workflow 发布与 Agent 挂载",
                "PASS",
                "已观察到当前页面的 PAGE_ASSISTANT Workflow 已发布并挂载到 Agent；这不等同于真实业务验收。",
                objectMapper.valueToTree(published));
    }

    private PageIntegrationReadinessItem modelReadiness(
            PublishedWorkflowView published) {
        if (published == null) {
            return item(
                    PageWorkbenchAgentModelReadinessApplicationService.KEY,
                    "Agent 模型可用性",
                    "PENDING",
                    "需要先发布并挂载页面 Workflow，才能确认对应 Agent 当前配置使用的模型。",
                    evidence());
        }
        ReadinessItem observed = modelReadiness.evaluate(
                published.modelInstanceId());
        return item(
                observed.key(),
                observed.label(),
                observed.status(),
                observed.message(),
                observed.evidence());
    }

    private PageIntegrationReadinessItem workflowTraceReadiness(
            String projectCode,
            String pageKey,
            boolean sessionReady,
            LocalDateTime observedAfter,
            PublishedWorkflowView published) {
        if (published == null) {
            return item(
                    PageWorkbenchWorkflowTraceReadinessApplicationService.KEY,
                    "页面 Workflow 执行链路",
                    "PENDING",
                    "需要先发布并挂载目标 PAGE_ASSISTANT Workflow，才能验证精确版本的真实执行 Trace。",
                    evidence());
        }
        if (!sessionReady || observedAfter == null) {
            return item(
                    PageWorkbenchWorkflowTraceReadinessApplicationService.KEY,
                    "页面 Workflow 执行链路",
                    "PENDING",
                    "需要先建立当前页面的真实 SDK 会话，才能验证该会话中的 Workflow 执行 Trace。",
                    objectMapper.valueToTree(published));
        }
        try {
            ReadinessItem observed = workflowTraceReadiness.evaluate(
                    projectCode,
                    pageKey,
                    observedAfter,
                    new PageWorkbenchWorkflowTraceReadinessApplicationService
                            .WorkflowAcceptanceTarget(
                            published.workflowId(),
                            published.workflowVersionId(),
                            published.workflowVersion(),
                            published.workflowName(),
                            published.modelInstanceId()),
                    null);
            return item(
                    observed.key(),
                    observed.label(),
                    observed.status(),
                    observed.message(),
                    observed.evidence());
        } catch (RuntimeException ex) {
            return item(
                    PageWorkbenchWorkflowTraceReadinessApplicationService.KEY,
                    "页面 Workflow 执行链路",
                    "WARN",
                    "Runtime 当前不可用，无法确认当前页面真实会话是否执行了指定 Workflow 版本。",
                    evidence().put("source", "reachai-runtime-service"));
        }
    }

    private PageIntegrationReadinessItem nodeExecutionReadiness(
            String key,
            String label,
            String requiredField,
            String observedField,
            String businessTerminalField,
            PageIntegrationReadinessItem workflowTrace,
            String notRequiredMessage,
            String pendingMessage) {
        if (workflowTrace == null) {
            return item(key, label, "PENDING", pendingMessage, evidence());
        }
        if ("WARN".equals(workflowTrace.status())) {
            return item(
                    key,
                    label,
                    "WARN",
                    "无法读取 Runtime Trace，暂不能确认该项是否已完成。",
                    workflowTrace.evidence());
        }
        JsonNode trace = workflowTrace.evidence() == null
                ? objectMapper.createObjectNode()
                : workflowTrace.evidence();
        boolean required = trace.path(requiredField).asBoolean(false);
        boolean observed = trace.path(observedField).asBoolean(false);
        boolean businessTerminal = StringUtils.hasText(businessTerminalField)
                && trace.path(businessTerminalField).asBoolean(false);
        if (!required && "PASS".equals(workflowTrace.status())) {
            return item(key, label, "NOT_REQUIRED", notRequiredMessage, trace);
        }
        if (required && observed && businessTerminal) {
            return item(
                    key,
                    label,
                    "PASS",
                    "ReachAI 已在当前页面的精确 Workflow Trace 中观察到该节点执行并返回业务终态；页面桥链路正常，但该次业务操作没有继续产生目标效果。",
                    trace);
        }
        if (required && observed) {
            return item(
                    key,
                    label,
                    "PASS",
                    "ReachAI 已在当前页面的精确 Workflow Trace 中观察到该节点成功执行。",
                    trace);
        }
        return item(key, label, "PENDING", pendingMessage, trace);
    }

    private PageIntegrationReadinessItem writeActionReadiness(
            PageView page,
            PlatformEmbedSessionEntity session,
            boolean sessionReady) {
        Set<String> writeActions = page.actions().stream()
                .filter(action -> "ACTIVE".equalsIgnoreCase(action.status()))
                .filter(action -> action.confirmRequired()
                        || "WRITE".equalsIgnoreCase(action.riskLevel())
                        || "IRREVERSIBLE".equalsIgnoreCase(action.riskLevel()))
                .map(PageWorkbenchContract.ActionView::actionKey)
                .filter(StringUtils::hasText)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (writeActions.isEmpty()) {
            return item(
                    "WRITE_ACTION_E2E_READY",
                    "写操作真实执行",
                    "NOT_REQUIRED",
                    "当前页面未声明需要验收的写操作，不把只读查询误报为写操作验收。",
                    evidence());
        }
        if (!sessionReady || session == null) {
            return item(
                    "WRITE_ACTION_E2E_READY",
                    "写操作真实执行",
                    "PENDING",
                    "已声明写操作，但尚未建立可观测的真实页面会话。",
                    evidence().set("actionKeys", objectMapper.valueToTree(writeActions)));
        }
        PlatformPageActionEventEntity event = actionEventMapper.selectOne(
                Wrappers.<PlatformPageActionEventEntity>lambdaQuery()
                        .eq(PlatformPageActionEventEntity::getSessionId, session.getSessionId())
                        .eq(PlatformPageActionEventEntity::getCommandType, "PAGE_ACTION")
                        .eq(PlatformPageActionEventEntity::getTargetPageKey, page.pageKey())
                        .in(PlatformPageActionEventEntity::getActionKey, writeActions)
                        .in(PlatformPageActionEventEntity::getStatus, List.of(
                                "SUCCESS",
                                "NO_DATA",
                                "PRECONDITION_FAILED",
                                "USER_CANCELLED",
                                "FAILED",
                                "CANCELLED",
                                "ACTION_NOT_FOUND",
                                "FORBIDDEN",
                                "TIMEOUT"))
                        .orderByDesc(PlatformPageActionEventEntity::getCompletedAt)
                        .last("LIMIT 1"));
        ObjectNode evidence = actionEventEvidence(event);
        evidence.set("expectedActionKeys", objectMapper.valueToTree(writeActions));
        if (event != null
                && writeActions.contains(event.getActionKey())
                && "SUCCESS".equalsIgnoreCase(event.getStatus())) {
            return item(
                    "WRITE_ACTION_E2E_READY",
                    "写操作真实执行",
                    "PASS",
                    "ReachAI 已观察到当前页面的已声明写操作完成并返回业务页面结果。",
                    evidence);
        }
        if (event != null && writeActions.contains(event.getActionKey())) {
            if (isBusinessTerminalStatus(event.getStatus())) {
                return item(
                        "WRITE_ACTION_E2E_READY",
                        "写操作真实执行",
                        "PENDING",
                        "已观察到写操作的业务终态 " + event.getStatus()
                                + "，但业务写入并未完成，不能作为写操作成功验收。",
                        evidence);
            }
            return item(
                    "WRITE_ACTION_E2E_READY",
                    "写操作真实执行",
                    "WARN",
                    "已观察到写操作回传，但当前状态为 " + event.getStatus() + "。",
                    evidence);
        }
        return item(
                "WRITE_ACTION_E2E_READY",
                "写操作真实执行",
                "PENDING",
                "尚未观察到当前页面已声明写操作的真实执行结果。",
                evidence);
    }

    private PublishedWorkflowView latestPublished(
            List<PublishedWorkflowView> published) {
        return (published == null ? List.<PublishedWorkflowView>of() : published)
                .stream()
                .max(Comparator.comparing(
                        PublishedWorkflowView::publishedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private static LocalDateTime observedAfter(
            PlatformEmbedSessionEntity session) {
        if (session == null) {
            return null;
        }
        return session.getCreatedAt() == null
                ? session.getUpdatedAt()
                : session.getCreatedAt();
    }

    private Set<String> readBridgeActions(String json) {
        if (!StringUtils.hasText(json)) {
            return Set.of();
        }
        try {
            List<String> values = objectMapper.readValue(
                    json,
                    new TypeReference<List<String>>() {
                    });
            LinkedHashSet<String> actions = new LinkedHashSet<>();
            for (String value : values == null
                    ? List.<String>of()
                    : values) {
                if (StringUtils.hasText(value)) {
                    actions.add(value.trim());
                }
            }
            return actions;
        } catch (Exception ex) {
            return Set.of();
        }
    }

    private PageIntegrationReadinessItem item(
            String key,
            String label,
            String status,
            String message,
            JsonNode evidence) {
        return new PageIntegrationReadinessItem(
                key,
                label,
                status,
                message,
                evidence);
    }

    private ObjectNode sessionEvidence(
            PlatformEmbedSessionEntity session) {
        ObjectNode node = evidence();
        if (session != null) {
            node.put("sessionId", safe(session.getSessionId()));
            node.put("pageInstanceId", safe(session.getPageInstanceId()));
            node.put("sdkVersion", safe(session.getSdkVersion()));
            node.put("route", safe(session.getRoute()));
            node.put(
                    "expiresAt",
                    session.getExpiresAt() == null
                            ? ""
                            : session.getExpiresAt().toString());
        }
        return node;
    }

    private ObjectNode actionEventEvidence(
            PlatformPageActionEventEntity event) {
        ObjectNode node = evidence();
        if (event != null) {
            node.put("requestId", safe(event.getRequestId()));
            node.put("actionKey", safe(event.getActionKey()));
            node.put("status", safe(event.getStatus()));
            node.put(
                    "completedAt",
                    event.getCompletedAt() == null
                            ? ""
                            : event.getCompletedAt().toString());
        }
        return node;
    }

    private ObjectNode evidence() {
        return objectMapper.createObjectNode();
    }

    private static boolean isBusinessTerminalStatus(String status) {
        return "NO_DATA".equalsIgnoreCase(status)
                || "PRECONDITION_FAILED".equalsIgnoreCase(status)
                || "USER_CANCELLED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private static String summaryStatus(
            List<PageIntegrationReadinessItem> items) {
        if (items.stream().anyMatch(item -> "FAIL".equals(item.status()))) {
            return "FAIL";
        }
        if (items.stream().allMatch(item -> "PASS".equals(item.status())
                || "NOT_REQUIRED".equals(item.status()))) {
            return "PASS";
        }
        return "PENDING";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
