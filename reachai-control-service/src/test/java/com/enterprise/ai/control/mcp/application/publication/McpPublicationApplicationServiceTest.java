package com.enterprise.ai.control.mcp.application.publication;

import com.enterprise.ai.control.mcp.application.CompositeMcpItemContractResolver;
import com.enterprise.ai.control.mcp.application.port.McpClientRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItem;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpPublicationApplicationServiceTest {

    @Test
    void listAcceptsMissingOptionalFilters() {
        McpPublicationRepository publicationRepository = mock(McpPublicationRepository.class);
        McpClientRepository clientRepository = mock(McpClientRepository.class);
        McpPrecheckService precheckService = mock(McpPrecheckService.class);
        CompositeMcpItemContractResolver contractResolver =
                mock(CompositeMcpItemContractResolver.class);
        McpPublicationRepository.Page expected =
                new McpPublicationRepository.Page(List.of(), 0);
        when(publicationRepository.findPage(null, null, 20, 0)).thenReturn(expected);
        McpPublicationApplicationService service = new McpPublicationApplicationService(
                publicationRepository, clientRepository, precheckService,
                contractResolver, new ObjectMapper());

        McpPublicationRepository.Page actual = service.list(null, null, null, null);

        assertEquals(expected, actual);
        verify(publicationRepository).findPage(null, null, 20, 0);
    }

    @Test
    void resolvePreviewUsesTheInjectedContractResolver() {
        McpPublicationRepository publicationRepository = mock(McpPublicationRepository.class);
        McpClientRepository clientRepository = mock(McpClientRepository.class);
        McpPrecheckService precheckService = mock(McpPrecheckService.class);
        CompositeMcpItemContractResolver contractResolver =
                mock(CompositeMcpItemContractResolver.class);
        McpPublication publication = new McpPublication(
                1L, "orders", "Orders MCP", McpPublicationStatus.DRAFT,
                null, null, null);
        McpPublicationItem item = new McpPublicationItem(
                10L, 1L, McpPublicationItemKind.CAPABILITY, "orders.lookup",
                "externalLookup", "External lookup", null, true, null, null);
        McpToolProjection resolved = new McpToolProjection(
                "lookup", "Lookup", "{\"type\":\"object\",\"properties\":{}}",
                McpPublicationItemKind.CAPABILITY, "orders.lookup", null, "READ");
        when(publicationRepository.findById(1L)).thenReturn(Optional.of(publication));
        when(publicationRepository.findItems(1L)).thenReturn(List.of(item));
        when(contractResolver.resolve(McpPublicationItemKind.CAPABILITY, "orders.lookup"))
                .thenReturn(resolved);
        McpPublicationApplicationService service = new McpPublicationApplicationService(
                publicationRepository, clientRepository, precheckService,
                contractResolver, new ObjectMapper());

        List<Map<String, Object>> preview = service.resolvePreview(1L);

        assertEquals(1, preview.size());
        assertEquals(true, preview.get(0).get("resolvable"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tool = (Map<String, Object>) preview.get(0).get("tool");
        assertEquals("externalLookup", tool.get("name"));
        assertEquals("External lookup", tool.get("description"));
        JsonNode schema = assertInstanceOf(JsonNode.class, tool.get("inputSchema"));
        assertTrue(schema.path("properties").isObject());
        verify(contractResolver).resolve(McpPublicationItemKind.CAPABILITY, "orders.lookup");
    }
}
