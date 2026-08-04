package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
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
        when(pageCatalog.findPage("orders", 1L))
                .thenReturn(Optional.of(page()));
        when(sessionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(session());
        when(eventMapper.selectOne(any(Wrapper.class)))
                .thenReturn(successfulEvent());
        PageWorkbenchPageReadinessApplicationService service =
                new PageWorkbenchPageReadinessApplicationService(
                        pageCatalog,
                        sessionMapper,
                        eventMapper,
                        new ObjectMapper());

        var result = service.evaluate("orders", 1L);

        assertEquals("PASS", result.status());
        assertEquals(5, result.items().size());
        assertEquals(
                "PASS",
                result.items().stream()
                        .filter(item -> "PAGE_ACTION_BINDING_READY"
                                .equals(item.key()))
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
        session.setUpdatedAt(LocalDateTime.now());
        return session;
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
