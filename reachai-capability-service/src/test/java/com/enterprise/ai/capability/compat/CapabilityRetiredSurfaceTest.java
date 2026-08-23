package com.enterprise.ai.capability.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CapabilityRetiredSurfaceTest {

    @Test
    void retiredCapabilityProxyControllerIsNotOnClasspath() {
        assertThrows(ClassNotFoundException.class, () ->
                Class.forName("com.enterprise.ai.capability.compat.CapabilityLegacyCompatibilityProxyController"));
    }

    @Test
    void retiredSkillMiningControllerIsNotOnClasspath() {
        assertThrows(ClassNotFoundException.class, () ->
                Class.forName("com.enterprise.ai.capability.catalog.mining.CapabilityMiningController"));
    }

    @Test
    void retiredSkillCatalogControllerIsNotOnClasspath() {
        assertThrows(ClassNotFoundException.class, () ->
                Class.forName("com.enterprise.ai.capability.catalog.composition.CapabilityCompositionCatalogController"));
    }
}
