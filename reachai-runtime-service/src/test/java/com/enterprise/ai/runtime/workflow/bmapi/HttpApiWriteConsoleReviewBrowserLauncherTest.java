package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Focused R1/R2 browser host: only UI actions discover/accept/connect/invoke; safe flags attest actual HTTP/H2. */
class HttpApiWriteConsoleReviewBrowserLauncherTest {
    @Test void browserMasksSourceDeclaredInputsRejectsNullAndQueriesOneSafeWrite() throws Exception {
        String directory = System.getProperty("bmapi.writeApiReviewBrowser.directory");
        assumeTrue(directory != null, "focused browser fixture requires an explicit fresh directory");
        Path root = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
        Path manifestPath = root.resolve("review-browser-manifest.json");
        for (String stage : List.of("discovered", "null-rejected", "success", "queried", "shutdown")) {
            if (Files.exists(root.resolve(stage + ".sentinel")) || Files.exists(manifestPath)) {
                throw new IllegalStateException("Use a fresh focused browser evidence directory");
            }
        }
        var json = new ObjectMapper().findAndRegisterModules();
        var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        var manifest = new LinkedHashMap<String, Object>(); var stages = new LinkedHashMap<String, Object>();
        long deadline = System.currentTimeMillis() + 45 * 60_000L;
        try {
            fixture.startWriteFixture(); var surface = fixture.prepareWriteApiBrowserSurface("order-notes-sensitive.yaml");
            manifest.put("controlOrigin", surface.controlOrigin()); manifest.put("projectId", 41L); manifest.put("projectCode", "orders");
            manifest.put("environment", "dev"); manifest.put("scanPath", surface.scanPath()); manifest.put("specFile", surface.specFile());
            manifest.put("upstreamOrigin", surface.upstreamOrigin()); manifest.put("credentialName", "Orders isolated API key");
            manifest.put("authStatePath", surface.authStatePath()); manifest.put("stages", stages);
            stages.put("initial", fixture.writeApiEvidence()); ready(manifestPath, json, manifest, "READY_FOR_REVIEW_DISCOVERY");

            await(root, "discovered", deadline);
            var owner = fixture.writeApiOwner(); assertNotNull(owner);
            assertEquals("DISCOVERED", owner.summary().sourceStatus()); assertEquals(List.of("OPENAPI_SCAN"), owner.summary().sourceKinds());
            assertEquals("password", owner.contract().at("/requestBody/schema/properties/accessCode/format").asText());
            assertTrue(owner.contract().at("/requestBody/schema/properties/deliveryMark/writeOnly").asBoolean());
            fixture.grantApiTrialAcl(); manifest.remove("authStatePath"); manifest.put("qualifiedName", owner.summary().qualifiedName());
            stages.put("discovered", fixture.writeApiEvidence()); ready(manifestPath, json, manifest, "READY_FOR_REVIEW_NULL_REJECTION");

            await(root, "null-rejected", deadline);
            fixture.assertWriteApiCounts(0, 0); assertEquals("ACCEPTED", fixture.writeApiOwner().summary().sourceStatus());
            assertEquals(1, fixture.writeApiEvidence().get("connections")); assertEquals(0, fixture.writeApiEvidence().get("publicPostRequests"));
            stages.put("nullRejected", fixture.writeApiEvidence()); ready(manifestPath, json, manifest, "READY_FOR_REVIEW_SAFE_WRITE");

            await(root, "success", deadline); fixture.assertWriteApiCounts(1, 1);
            assertEquals(1, fixture.writeApiEvidence().get("publicPostRequests"));
            var actual = fixture.writeApi.lastBody.get(); assertNotNull(actual);
            assertTrue(actual.get("accessCode") instanceof String); assertTrue(actual.get("deliveryMark") instanceof String);
            assertEquals(false, actual.get("notify")); assertEquals(0, actual.get("quantity")); assertEquals("", actual.get("caption"));
            assertFalse(actual.containsKey("priority"));
            List<String> privateValues = List.of((String) actual.get("accessCode"), (String) actual.get("deliveryMark"));
            var leaks = fixture.writeApiSensitiveAuditLeaks(privateValues); assertFalse(leaks.containsValue(true), "private input found in persisted review boundaries");
            stages.put("safeWrite", fixture.writeApiEvidence()); manifest.put("auditLeakFlags", leaks);
            manifest.put("actualBodyFacts", Map.of("passwordFormatPresent", true, "writeOnlyPresent", true,
                    "notify", false, "quantity", 0, "captionEmpty", true, "priorityAbsent", true));
            ready(manifestPath, json, manifest, "READY_FOR_REVIEW_SAME_ID_QUERY");

            await(root, "queried", deadline); fixture.assertWriteApiCounts(1, 1);
            assertEquals(1, fixture.writeApiEvidence().get("publicPostRequests"));
            assertTrue(((Number) fixture.writeApiEvidence().get("publicQueryRequests")).intValue() >= 1);
            assertFalse(fixture.writeApiSensitiveAuditLeaks(privateValues).containsValue(true));
            stages.put("sameIdQuery", fixture.writeApiEvidence()); ready(manifestPath, json, manifest, "BROWSER_REVIEW_VERIFIED");
            await(root, "shutdown", deadline);
        } finally {
            manifest.remove("authStatePath"); if (Files.exists(manifestPath)) write(manifestPath, json, manifest); fixture.close();
        }
    }

    private static void await(Path root, String stage, long deadline) throws Exception {
        while (!Files.exists(root.resolve(stage + ".sentinel")) && !Files.exists(root.resolve("shutdown.sentinel"))
                && System.currentTimeMillis() < deadline) Thread.sleep(200);
        if (!Files.exists(root.resolve(stage + ".sentinel"))) throw new IllegalStateException("Missing focused browser stage: " + stage);
    }
    private static void ready(Path path, ObjectMapper json, Map<String, Object> manifest, String status) throws Exception {
        manifest.put("status", status); write(path, json, manifest); System.out.println("BMAPI_3B4_REVIEW_" + status);
    }
    private static void write(Path path, ObjectMapper json, Object value) throws Exception {
        Files.writeString(path, json.writeValueAsString(value), StandardCharsets.UTF_8);
    }
}
