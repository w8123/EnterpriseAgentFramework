package com.enterprise.ai.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositionControllerNamingTest {

    @Test
    void exposesCompositionAsPrimaryRouteAndKeepsSkillsAsCompatibilityAlias() {
        RequestMapping mapping = CompositionController.class.getAnnotation(RequestMapping.class);

        assertTrue(Arrays.asList(mapping.value()).contains("/api/compositions"));
        assertTrue(Arrays.asList(mapping.value()).contains("/api/skills"));
    }
}
