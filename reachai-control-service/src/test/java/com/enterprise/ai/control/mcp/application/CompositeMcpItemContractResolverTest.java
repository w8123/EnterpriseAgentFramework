package com.enterprise.ai.control.mcp.application;

import com.enterprise.ai.control.mcp.application.port.McpContractResolutionException;
import com.enterprise.ai.control.mcp.application.port.McpItemContractResolver;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeMcpItemContractResolverTest {

    @Test
    void routesBySourceKindToRegisteredResolver() {
        McpItemContractResolver capabilityResolver = mock(McpItemContractResolver.class);
        McpItemContractResolver workflowResolver = mock(McpItemContractResolver.class);
        when(capabilityResolver.supportedKind()).thenReturn(McpPublicationItemKind.CAPABILITY);
        when(workflowResolver.supportedKind()).thenReturn(McpPublicationItemKind.WORKFLOW);
        McpToolProjection capabilityProjection = new McpToolProjection(
                "order_cancel", "取消订单", "{}", McpPublicationItemKind.CAPABILITY, "orders:order.cancel", null, "WRITE");
        McpToolProjection workflowProjection = new McpToolProjection(
                "order_sync", "订单同步", "{}", McpPublicationItemKind.WORKFLOW, "wf-1", 9L, "WRITE");
        when(capabilityResolver.resolve("orders:order.cancel")).thenReturn(capabilityProjection);
        when(workflowResolver.resolve("wf-1")).thenReturn(workflowProjection);
        CompositeMcpItemContractResolver composite =
                new CompositeMcpItemContractResolver(List.of(capabilityResolver, workflowResolver));

        assertSame(capabilityProjection, composite.resolve(McpPublicationItemKind.CAPABILITY, "orders:order.cancel"));
        assertSame(workflowProjection, composite.resolve(McpPublicationItemKind.WORKFLOW, "wf-1"));
    }

    @Test
    void unknownSourceKindFailsWithStableCode() {
        CompositeMcpItemContractResolver composite =
                new CompositeMcpItemContractResolver(List.of());

        McpContractResolutionException exception = assertThrows(McpContractResolutionException.class,
                () -> composite.resolve(McpPublicationItemKind.WORKFLOW, "wf-1"));
        assertEquals("MCP_SOURCE_KIND_UNSUPPORTED", exception.code());
        assertEquals(McpPublicationItemKind.WORKFLOW, exception.sourceKind());
    }

    @Test
    void duplicateKindRegistrationFailsAtStartup() {
        McpItemContractResolver first = mock(McpItemContractResolver.class);
        McpItemContractResolver second = mock(McpItemContractResolver.class);
        when(first.supportedKind()).thenReturn(McpPublicationItemKind.CAPABILITY);
        when(second.supportedKind()).thenReturn(McpPublicationItemKind.CAPABILITY);

        assertThrows(IllegalStateException.class,
                () -> new CompositeMcpItemContractResolver(List.of(first, second)));
    }
}
