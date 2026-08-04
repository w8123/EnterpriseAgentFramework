package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageIntegrationReadinessItem;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageIntegrationReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.platform.PlatformEmbedSessionEntity;
import com.enterprise.ai.control.platform.PlatformEmbedSessionMapper;
import com.enterprise.ai.control.platform.PlatformPageActionEventEntity;
import com.enterprise.ai.control.platform.PlatformPageActionEventMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
                                        "FAILED",
                                        "CANCELLED",
                                        "ACTION_NOT_FOUND",
                                        "FORBIDDEN",
                                        "TIMEOUT"))
                        .orderByDesc(
                                PlatformPageActionEventEntity::getCompletedAt)
                        .last("LIMIT 1"));
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

    private static String summaryStatus(
            List<PageIntegrationReadinessItem> items) {
        if (items.stream().anyMatch(item -> "FAIL".equals(item.status()))) {
            return "FAIL";
        }
        if (items.stream().allMatch(item -> "PASS".equals(item.status()))) {
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
