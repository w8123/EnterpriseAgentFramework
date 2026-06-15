package com.enterprise.ai.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentStudioCompatibilityControllerTest {

    @Test
    void oldAgentStudioControllersAreExplicitCompatibilityEntrypoints() {
        assertDeprecatedCompatibilityController(AgentStudioDraftController.class);
        assertDeprecatedCompatibilityController(AgentStudioDebugController.class);
    }

    private void assertDeprecatedCompatibilityController(Class<?> controllerType) {
        RequestMapping mapping = controllerType.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/agent/studio"}, mapping.value());
        assertNotNull(controllerType.getAnnotation(Deprecated.class));
    }
}
