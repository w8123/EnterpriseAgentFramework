package com.enterprise.ai.runtime.contract.memory;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessMemoryContractResourceTest {

    @Test
    void packagesReferenceAndResolutionSchemas() throws Exception {
        assertSchema("contracts/reachai-business-memory-reference-v1.schema.json",
                BusinessMemoryReference.SCHEMA, "resolverCapabilityKey");
        assertSchema("contracts/reachai-business-memory-resolution-v1.schema.json",
                BusinessMemoryResolution.SCHEMA, "resolvedAt");
    }

    private void assertSchema(String path, String schema, String requiredField) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path + " must be packaged in ai-runtime-contract");
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"const\": \"" + schema + "\""));
            assertTrue(json.contains("\"" + requiredField + "\""));
        }
    }
}
