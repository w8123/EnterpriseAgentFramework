package com.enterprise.ai.spring.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EafAgentClientContractTest {

    @Test
    void activeAgentCallDefaultsToCurrentBusinessUserPrincipal() {
        EafRegistryProperties properties = new EafRegistryProperties();
        properties.getProject().setCode("bzsdk");
        EafCurrentUserProvider provider = () -> new EafUser(
                "ADMIN001",
                "emp-001",
                "系统管理员",
                "D001",
                "研发中心",
                List.of("admin"),
                Map.of("orgId", "org-001"));
        EafAgentClient client = new EafAgentClient(properties, provider);

        EafAgentClient.AgentChatRequest request = client.requestForCurrentUser("hello", null, Map.of("route", "/team"));

        assertEquals("hello", request.message());
        assertEquals("ADMIN001", request.userId());
        assertEquals(List.of("admin"), request.roles());
        assertEquals("bzsdk", request.context().get("appId"));
        assertEquals("bzsdk", request.context().get("projectCode"));
        assertEquals("emp-001", request.context().get("globalUserId"));
        assertEquals("系统管理员", request.context().get("userName"));
        assertEquals("org-001", ((Map<?, ?>) request.context().get("attributes")).get("orgId"));
    }
}
