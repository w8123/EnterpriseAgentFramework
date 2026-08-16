package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService;
import com.enterprise.ai.control.platform.PlatformEmbedSessionEntity;
import com.enterprise.ai.control.platform.PlatformEmbedSessionMapper;
import com.enterprise.ai.control.platform.PlatformPageActionEventEntity;
import com.enterprise.ai.control.platform.PlatformPageActionEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageWorkbenchPageReadinessApplicationServiceTest {

    @Test
    void passesOnlyWhenCatalogSessionBindingAndRuntimeResultAreObserved() {
        PageCatalogApplicationService pageCatalog =
                mock(PageCatalogApplicationService.class);
        PlatformEmbedSessionMapper sessionMapper =
                mock(PlatformEmbedSessionMapper.class);
        PlatformPageActionEventMapper eventMapper =
                mock(PlatformPageActionEventMapper.class);
        PlatformEmbedE2eEvidenceService embedEvidence =
                mock(PlatformEmbedE2eEvidenceService.class);
        PageWorkbenchPublishedApplicationService publishedService =
                mock(PageWorkbenchPublishedApplicationService.class);
        PageWorkbenchAgentModelReadinessApplicationService modelReadiness =
                mock(PageWorkbenchAgentModelReadinessApplicationService.class);
        PageWorkbenchWorkflowTraceReadinessApplicationService workflowTraceReadiness =
                mock(PageWorkbenchWorkflowTraceReadinessApplicationService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        when(pageCatalog.findPage("orders", 1L))
                .thenReturn(Optional.of(page()));
        when(sessionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(session());
        when(eventMapper.selectOne(any(Wrapper.class)))
                .thenReturn(successfulEvent());
        when(embedEvidence.latestSuccessfulConversation(any(), any(), any()))
                .thenReturn(PlatformEmbedE2eEvidenceService.EmbedConversationEvidence.passed(
                        "observed browser conversation",
                        "session=es-orders"));
        when(publishedService.list("orders", "orders.detail"))
                .thenReturn(List.of(published()));
        when(modelReadiness.evaluate("model-orders"))
                .thenReturn(new ReadinessItem(
                        PageWorkbenchAgentModelReadinessApplicationService.KEY,
                        "Agent 模型可用性",
                        "PASS",
                        "model ready",
                        objectMapper.createObjectNode()));
        when(workflowTraceReadiness.evaluate(any(), any(), any(), any(), isNull()))
                .thenReturn(new ReadinessItem(
                        "PAGE_WORKFLOW_TRACE_READY",
                        "workflow trace",
                        "PASS",
                        "observed workflow trace",
                        objectMapper.createObjectNode()
                                .put("capabilityRequired", false)
                                .put("capabilityObserved", false)
                                .put("pageActionRequired", false)
                                .put("pageActionObserved", false)));
        PageWorkbenchPageReadinessApplicationService service =
                new PageWorkbenchPageReadinessApplicationService(
                        pageCatalog,
                        sessionMapper,
                        eventMapper,
                        embedEvidence,
                        publishedService,
                        modelReadiness,
                        workflowTraceReadiness,
                        objectMapper);

        var result = service.evaluate("orders", 1L);

        assertEquals(
                "PASS",
                result.status(),
                () -> result.items().stream()
                        .map(item -> item.key() + "=" + item.status())
                        .toList()
                        .toString());
        assertEquals(13, result.items().size());
        assertEquals(
                "PASS",
                result.items().stream()
                        .filter(item -> "BUSINESS_PAGE_URL_READY".equals(item.key()))
                        .findFirst()
                        .orElseThrow()
                        .status());
        assertEquals(
                "PASS",
                result.items().stream()
                        .filter(item -> "PAGE_ACTION_BINDING_READY"
                                .equals(item.key()))
                        .findFirst()
                        .orElseThrow()
                        .status());
        assertEquals(
                "PASS",
                result.items().stream()
                        .filter(item -> "PAGE_BROWSER_E2E_READY".equals(item.key()))
                        .findFirst()
                        .orElseThrow()
                        .status());
        assertEquals(
                "NOT_REQUIRED",
                result.items().stream()
                        .filter(item -> "CAPABILITY_TOOL_E2E_READY".equals(item.key()))
                        .findFirst()
                        .orElseThrow()
                        .status());
    }

    private PageView page() {
        return new PageView(
                1L,
                7L,
                "orders",
                "orders.detail",
                "orders",
                "订单",
                "订单详情",
                null,
                "/orders/:id",
                "http://localhost:5173/orders/1",
                "src/views/orders/Detail.vue",
                "AI_CODING",
                "ACTIVE",
                null,
                null,
                List.of(),
                List.of(action()));
    }

    private ActionView action() {
        return new ActionView(
                11L,
                1L,
                7L,
                "orders",
                "orders.detail",
                "getPageState",
                "读取页面状态",
                null,
                "QUERY",
                "LOW",
                false,
                null,
                null,
                null,
                null,
                List.of(),
                "src/views/orders/Detail.vue",
                "MANUAL",
                "ACTIVE",
                null,
                null);
    }

    private PlatformEmbedSessionEntity session() {
        PlatformEmbedSessionEntity session =
                new PlatformEmbedSessionEntity();
        session.setSessionId("es-orders");
        session.setProjectCode("orders");
        session.setPageKey("orders.detail");
        session.setPageInstanceId("page-orders-1");
        session.setSdkVersion("1.0.0");
        session.setBridgeActionsJson("[\"getPageState\"]");
        session.setStatus("ACTIVE");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        session.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    private PublishedWorkflowView published() {
        return new PublishedWorkflowView(
                "orders.detail",
                "workflow-orders",
                "orders-page-assistant",
                "订单页面助手",
                null,
                "ACTIVE",
                101L,
                "v1.0.0",
                "tester",
                LocalDateTime.now().minusMinutes(1),
                "agent-orders",
                "orders-copilot",
                "订单助手",
                201L,
                1,
                "model-orders",
                "orders_page_assistant",
                "READ",
                null,
                1,
                1.0,
                LocalDateTime.now(),
                "SUCCESS",
                "trace-orders");
    }

    private PlatformPageActionEventEntity successfulEvent() {
        PlatformPageActionEventEntity event =
                new PlatformPageActionEventEntity();
        event.setRequestId("par-orders");
        event.setSessionId("es-orders");
        event.setCommandType("PAGE_ACTION");
        event.setTargetPageKey("orders.detail");
        event.setActionKey("getPageState");
        event.setStatus("SUCCESS");
        event.setCompletedAt(LocalDateTime.now());
        return event;
    }
}
