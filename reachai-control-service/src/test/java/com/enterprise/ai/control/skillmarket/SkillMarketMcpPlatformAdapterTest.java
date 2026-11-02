package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillMarketMcpPlatformAdapterTest {
    private final SkillMarketController market = mock(SkillMarketController.class);
    private final PlatformDelegatedRequestScope scope = mock(PlatformDelegatedRequestScope.class);
    private final SkillMarketMcpPlatformAdapter adapter = new SkillMarketMcpPlatformAdapter(market, scope);
    private final PlatformAuthenticatedSession actor = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"),
            "session", null, List.of(), List.of("skill:read", "skill:import"), List.of());
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @SuppressWarnings("unchecked")
    private void delegate() {
        when(scope.run(eq(actor), any())).thenAnswer(call -> ((Function<HttpServletRequest, ResponseEntity<?>>) call.getArgument(1)).apply(request));
    }

    @Test void ownerFailuresExposeOnlyTheStableCode() {
        delegate();
        when(market.probe(eq(request), any())).thenThrow(new AgentSkillException("SKILL_MARKET_SOURCE_CHANGED", HttpStatus.CONFLICT, "private repo detail"));
        var response = adapter.probe(actor, Map.of("sourceUrl", "https://github.com/acme/skills"));
        assertEquals(409, response.getStatusCode().value());
        assertEquals(Map.of("code", "SKILL_MARKET_SOURCE_CHANGED"), response.getBody());
    }

    @Test void aLostImportIsUnknownAndNotRetried() {
        delegate();
        when(market.importSkill(eq(request), any())).thenThrow(new IllegalStateException("database connection lost"));
        var response = adapter.importSkill(actor, Map.of("sourceUrl", "https://github.com/acme/skills", "commitSha", "a".repeat(40),
                "sourceRoot", "orders", "expectedSelectedSourceSha256", "b".repeat(64), "visibility", "PRIVATE"));
        assertEquals(202, response.getStatusCode().value());
        assertFalse(String.valueOf(response.getBody()).contains("database"));
        verify(market, times(1)).importSkill(eq(request), any());
    }
}
