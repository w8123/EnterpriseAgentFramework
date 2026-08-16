package com.enterprise.ai.control.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalMemoryKnowledgeQueryModeTest {

    @Test
    void supportsExplicitModesAndLegacyBooleanValues() {
        assertEquals(PersonalMemoryKnowledgeQueryMode.OFF,
                PersonalMemoryKnowledgeQueryMode.parse("off"));
        assertEquals(PersonalMemoryKnowledgeQueryMode.SHADOW,
                PersonalMemoryKnowledgeQueryMode.parse("shadow"));
        assertEquals(PersonalMemoryKnowledgeQueryMode.ACTIVE,
                PersonalMemoryKnowledgeQueryMode.parse("active"));
        assertEquals(PersonalMemoryKnowledgeQueryMode.ACTIVE,
                PersonalMemoryKnowledgeQueryMode.parse("true"));
        assertEquals(PersonalMemoryKnowledgeQueryMode.OFF,
                PersonalMemoryKnowledgeQueryMode.parse("false"));
    }

    @Test
    void rejectsUnknownModeInsteadOfSilentlyChangingProductionBehavior() {
        assertThrows(IllegalArgumentException.class,
                () -> PersonalMemoryKnowledgeQueryMode.parse("experimental"));
    }
}
