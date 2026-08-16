package com.enterprise.ai.personalmemory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalMemoryEmbeddingPropertiesTest {

    @Test
    void lexicalModeDoesNotRequireAModel() {
        assertDoesNotThrow(new PersonalMemoryEmbeddingProperties()::afterPropertiesSet);
    }

    @Test
    void semanticModesRequireAConfiguredModelInstance() {
        PersonalMemoryEmbeddingProperties properties = new PersonalMemoryEmbeddingProperties();
        properties.setMode(PersonalMemoryEmbeddingProperties.Mode.HYBRID);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsUnboundedCandidateConfiguration() {
        PersonalMemoryEmbeddingProperties properties = new PersonalMemoryEmbeddingProperties();
        properties.setCandidateLimit(5000);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsNegativeCosineThresholdsThatCouldNeverProduceAPositiveRank() {
        PersonalMemoryEmbeddingProperties properties = new PersonalMemoryEmbeddingProperties();
        properties.setMinScore(-0.1f);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }
}
