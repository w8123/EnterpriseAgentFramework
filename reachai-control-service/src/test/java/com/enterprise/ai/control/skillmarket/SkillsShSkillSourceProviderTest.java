package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchResult;
import com.enterprise.ai.control.skillmarket.SkillMarketHttpTransport.HttpPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillsShSkillSourceProviderTest {

    private SkillMarketProperties properties;
    private SkillMarketHttpTransport transport;
    private SkillsShSkillSourceProvider provider;

    @BeforeEach
    void setUp() {
        properties = new SkillMarketProperties();
        transport = mock(SkillMarketHttpTransport.class);
        provider = new SkillsShSkillSourceProvider(properties, transport, new ObjectMapper());
    }

    @Test
    void usesPublicSearchFallbackWithoutPretendingItIsAuthenticated() {
        String json = """
                {"skills":[{"id":"vercel-labs/agent-skills/demo","skillId":"demo",
                "name":"demo","installs":123,"source":"vercel-labs/agent-skills"}]}
                """;
        when(transport.get(any(), any(), any(), anyLong()))
                .thenReturn(payload(200, json));

        SearchResult result = provider.search(new SearchRequest("react", "all-time", null, 10));

        assertEquals("LEGACY_PUBLIC_SEARCH", result.mode());
        assertFalse(result.authenticatedUpstream());
        assertEquals(1, result.items().size());
        assertEquals("https://github.com/vercel-labs/agent-skills", result.items().get(0).installUrl());
        assertTrue(result.items().get(0).importable());
    }

    @Test
    void usesOfficialV1WhenOidcTokenIsConfigured() {
        properties.getSkillsSh().setOidcToken("runtime-only-token");
        String json = """
                {"data":[{"id":"anthropics/skills/pdf","slug":"pdf","name":"PDF",
                "source":"anthropics/skills","sourceType":"github","installs":77,
                "installUrl":"https://github.com/anthropics/skills",
                "url":"https://skills.sh/anthropics/skills/pdf"}]}
                """;
        when(transport.get(any(), any(), any(), anyLong()))
                .thenReturn(payload(200, json));

        SearchResult result = provider.search(new SearchRequest("pdf", "all-time", null, 5));

        assertEquals("OFFICIAL_V1", result.mode());
        assertTrue(result.authenticatedUpstream());
        assertEquals("PDF", result.items().get(0).name());
    }

    @Test
    void blankQueryWithoutOidcReturnsExplicitDiscoveryOnlyState() {
        SearchResult result = provider.search(new SearchRequest(null, "trending", null, 10));

        assertEquals("SOURCE_DISCOVERY_ONLY", result.mode());
        assertTrue(result.items().isEmpty());
        verify(transport, never()).get(any(), any(), any(), anyLong());
    }

    @Test
    void rejectsSingleCharacterQueriesBeforeCallingUpstream() {
        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> provider.search(new SearchRequest("a", "all-time", null, 10)));

        assertEquals("SKILL_MARKET_REQUEST_INVALID", failure.code());
        verify(transport, never()).get(any(), any(), any(), anyLong());
    }

    private HttpPayload payload(int status, String body) {
        return new HttpPayload(status, URI.create("https://skills.sh/api/search"), Map.of(),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
