package com.enterprise.ai.control.aicoding.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodingArtifactContractValidatorTest {

    private static final List<String> CONTRACTS = List.of(
            "project-onboarding-report-v1",
            "page-map-report-v1",
            "page-analysis-report-v1",
            "code-implementation-report-v1",
            "browser-acceptance-report-v1",
            "pre-release-report-v1");

    private ObjectMapper objectMapper;
    private AiCodingContractResourceLoader loader;
    private AiCodingArtifactContractValidator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        loader = new AiCodingContractResourceLoader(objectMapper);
        validator = new AiCodingArtifactContractValidator(loader);
    }

    @Test
    void acceptsEveryPublishedContractExample() {
        for (String contract : CONTRACTS) {
            JsonNode schema = loader.load(contract + ".schema.json");
            JsonNode example = loader.load(contract + ".example.json");
            assertDoesNotThrow(
                    () -> validator.requireValid(example, schema),
                    contract);
        }
    }

    @Test
    void rejectsMissingRequiredPrimitiveInsteadOfUsingJavaDefault() {
        JsonNode schema = loader.load(
                "browser-acceptance-report-v1.schema.json");
        JsonNode content = loader.load(
                "browser-acceptance-report-v1.example.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) content)
                .remove("passed");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(content, schema));
        assertTrue(error.getMessage().contains("$.passed"));
    }

    @Test
    void rejectsUnknownFieldsAndInvalidNestedEnums() {
        JsonNode schema = loader.load(
                "code-implementation-report-v1.schema.json");
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) loader.load(
                "code-implementation-report-v1.example.json").deepCopy();
        content.put("credential", "must-not-be-accepted");
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(content, schema));

        content.remove("credential");
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                content.path("tests").path(0)).put("status", "SUCCESS");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(content, schema));
        assertTrue(error.getMessage().contains("$.tests[0].status"));
    }

    @Test
    void enforcesExternalReferences() {
        JsonNode schema = loader.load("pre-release-report-v1.schema.json");
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) loader.load(
                "pre-release-report-v1.example.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                content.path("checks").path(0)).remove("evidence");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(content, schema));
        assertTrue(error.getMessage().contains("$.checks[0].evidence"));
    }

    @Test
    void projectOnboardingBrowserEvidenceRequiresVisibleDeliveryMaterial() {
        JsonNode schema = loader.load(
                "project-onboarding-report-v1.schema.json");
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) loader.load(
                "project-onboarding-report-v1.example.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                content.path("browserVerification")).remove("screenshots");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(content, schema));
        assertTrue(
                error.getMessage().contains("$.browserVerification"),
                error.getMessage());
    }

    @Test
    void finalImplementationRequiresChangedFilesTestsAndBrowserMaterial() {
        JsonNode schema = loader.load(
                "code-implementation-report-v1.schema.json");
        var missingChangedFiles = (com.fasterxml.jackson.databind.node.ObjectNode) loader.load(
                "code-implementation-report-v1.example.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)
                missingChangedFiles.path("changedFiles")).removeAll();
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(missingChangedFiles, schema));

        var missingTests = (com.fasterxml.jackson.databind.node.ObjectNode) loader.load(
                "code-implementation-report-v1.example.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)
                missingTests.path("tests")).removeAll();
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(missingTests, schema));

        var missingScreenshots = (com.fasterxml.jackson.databind.node.ObjectNode) loader.load(
                "code-implementation-report-v1.example.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                missingScreenshots.path("browserVerification"))
                .remove("screenshots");
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(missingScreenshots, schema));
    }

    @Test
    void enforcesFindingLimitAndDateTimeFormat() {
        JsonNode analysisSchema = loader.load(
                "page-analysis-report-v1.schema.json");
        var analysis = (com.fasterxml.jackson.databind.node.ObjectNode) loader.load(
                "page-analysis-report-v1.example.json").deepCopy();
        var findings = (com.fasterxml.jackson.databind.node.ArrayNode)
                analysis.path("findings");
        while (findings.size() < 4) {
            findings.add(findings.path(0).deepCopy());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(analysis, analysisSchema));

        JsonNode pageMapSchema = loader.load(
                "page-map-report-v1.schema.json");
        var pageMap = (com.fasterxml.jackson.databind.node.ObjectNode) loader.load(
                "page-map-report-v1.example.json").deepCopy();
        pageMap.put("scannedAt", "yesterday afternoon");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.requireValid(pageMap, pageMapSchema));
        assertTrue(error.getMessage().contains("$.scannedAt"));
    }
}
