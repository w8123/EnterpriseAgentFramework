package com.enterprise.ai.control.aicoding.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodingContractResourceLoaderTest {

    @Test
    void bundlesExternalSchemasNeededForLocalArtifactValidation() {
        ObjectMapper objectMapper = new ObjectMapper();
        AiCodingContractResourceLoader loader = new AiCodingContractResourceLoader(objectMapper);

        var references = loader.referencedSchemas(
                loader.load("project-onboarding-report-v1.schema.json"));

        assertEquals(1, references.size());
        assertTrue(references.containsKey("delivery-evidence-v1.schema.json"));
        assertTrue(references.get("delivery-evidence-v1.schema.json")
                .path("$defs")
                .has("browserVerification"));
    }
}
