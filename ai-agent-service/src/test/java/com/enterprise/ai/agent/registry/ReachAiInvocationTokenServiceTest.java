package com.enterprise.ai.agent.registry;

import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReachAiInvocationTokenServiceTest {

    @Test
    void createsInvocationTokenFromRegistryCredentialAndRuntimeContext() {
        RegistryCredentialMapper mapper = mock(RegistryCredentialMapper.class);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("bzsdk");
        credential.setAppKey("demo-key");
        credential.setAppSecret("secret");
        credential.setStatus("ACTIVE");
        when(mapper.selectOne(any())).thenReturn(credential);

        ReachAiInvocationTokenService service = new ReachAiInvocationTokenService(mapper);
        ToolDefinitionEntity definition = new ToolDefinitionEntity();
        definition.setName("contract.query");
        definition.setProjectCode("bzsdk");
        ToolExecutionContext context = ToolExecutionContext.builder()
                .externalUserId("ADMIN001")
                .globalUserId("emp-0001")
                .roles(List.of("admin"))
                .agentId("team-agent")
                .sessionId("session-1")
                .traceId("trace-1")
                .build();

        String token = service.createToken(definition, context);
        ReachAiInvocationClaims claims = ReachAiInvocationToken.verify(
                "secret", token, "bzsdk", "contract.query", System.currentTimeMillis());

        assertEquals("demo-key", claims.getAppKey());
        assertEquals("ADMIN001", claims.getExternalUserId());
        assertEquals("emp-0001", claims.getGlobalUserId());
        assertEquals(List.of("admin"), claims.getRoles());
        assertEquals("team-agent", claims.getAgentId());
        assertEquals("session-1", claims.getSessionId());
        assertEquals("trace-1", claims.getTraceId());
    }
}
