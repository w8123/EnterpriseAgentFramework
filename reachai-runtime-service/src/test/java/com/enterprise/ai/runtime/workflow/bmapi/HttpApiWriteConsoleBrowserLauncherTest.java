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

/** Sentinels attest browser actions; only the real browser discovers, accepts, connects and invokes the API. */
class HttpApiWriteConsoleBrowserLauncherTest {
    @Test void browserScansAcceptsConnectsCancelsWritesQueriesUnknownAndConfirmsNewId() throws Exception {
        String directory = System.getProperty("bmapi.writeApiBrowser.directory");
        assumeTrue(directory != null, "real write API browser fixture requires an explicit fresh directory");
        Path root = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
        Path manifestPath = root.resolve("write-api-browser-manifest.json");
        for (String stage : List.of("discovered", "cancel", "success", "unknown", "queried", "new-prepared", "new-attempt", "denied", "shutdown")) {
            if (Files.exists(root.resolve(stage + ".sentinel")) || Files.exists(manifestPath)) throw new IllegalStateException("Use a fresh browser evidence directory");
        }
        var json = new ObjectMapper().findAndRegisterModules();
        var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        var manifest = new LinkedHashMap<String, Object>(); var stages = new LinkedHashMap<String, Object>();
        long deadline = System.currentTimeMillis() + 90 * 60_000L;
        try {
            fixture.startWriteFixture(); var surface = fixture.prepareWriteApiBrowserSurface();
            manifest.put("controlOrigin", surface.controlOrigin()); manifest.put("projectId", 41L); manifest.put("projectCode", "orders");
            manifest.put("environment", "dev"); manifest.put("scanPath", surface.scanPath()); manifest.put("specFile", surface.specFile());
            manifest.put("upstreamOrigin", surface.upstreamOrigin()); manifest.put("credentialName", "Orders isolated API key");
            manifest.put("authStatePath", surface.authStatePath()); manifest.put("stages", stages);
            stages.put("initial", fixture.writeApiEvidence()); ready(manifestPath, json, manifest, "READY_FOR_BROWSER_DISCOVERY");
            await(root, "discovered", deadline);
            var owner = fixture.multiSourceDetail(); assertNotNull(owner); assertEquals("DISCOVERED", owner.summary().sourceStatus());
            assertEquals("POST", owner.summary().httpMethod()); assertEquals("WRITE", owner.contract().path("sideEffect").asText());
            assertEquals(List.of("OPENAPI_SCAN"), owner.summary().sourceKinds()); fixture.grantApiTrialAcl();
            manifest.remove("authStatePath"); manifest.put("qualifiedName", owner.summary().qualifiedName());
            stages.put("discovered", fixture.writeApiEvidence()); ready(manifestPath, json, manifest, "READY_FOR_BROWSER_ACCEPT_CONNECT_CANCEL");

            await(root, "cancel", deadline); fixture.assertWriteApiCounts(0, 0);
            assertEquals("ACCEPTED", fixture.multiSourceDetail().summary().sourceStatus());
            assertEquals(1, fixture.writeApiEvidence().get("connections")); assertEquals(0, fixture.writeApiEvidence().get("publicPostRequests"));
            stages.put("cancel", fixture.writeApiEvidence()); ready(manifestPath, json, manifest, "READY_FOR_BROWSER_SUCCESS");

            await(root, "success", deadline); fixture.assertWriteApiCounts(1, 1); assertLatest(fixture.writeApiEvidence(), "SUCCEEDED");
            assertEquals(1, fixture.writeApiEvidence().get("publicPostRequests")); stages.put("success", fixture.writeApiEvidence());
            fixture.dropNextWriteApiResponse(); ready(manifestPath, json, manifest, "READY_FOR_BROWSER_UNKNOWN");

            await(root, "unknown", deadline); fixture.assertWriteApiCounts(2, 2); assertLatest(fixture.writeApiEvidence(), "UNKNOWN");
            assertEquals(2, fixture.writeApiEvidence().get("publicPostRequests")); stages.put("unknown", fixture.writeApiEvidence());
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_QUERY_REOPEN_RELOAD");

            await(root, "queried", deadline); fixture.assertWriteApiCounts(2, 2); assertLatest(fixture.writeApiEvidence(), "UNKNOWN");
            assertEquals(2, fixture.writeApiEvidence().get("publicPostRequests"));
            assertTrue(((Number) fixture.writeApiEvidence().get("publicQueryRequests")).intValue() >= 3);
            stages.put("queryReopenReload", fixture.writeApiEvidence()); ready(manifestPath, json, manifest, "READY_FOR_BROWSER_NEW_CONFIRMATION");

            await(root, "new-prepared", deadline); fixture.assertWriteApiCounts(2, 2);
            assertEquals(2, fixture.writeApiEvidence().get("publicPostRequests")); stages.put("newConfirmationBeforePost", fixture.writeApiEvidence());
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_NEW_POST");

            await(root, "new-attempt", deadline); fixture.assertWriteApiCounts(3, 3); assertLatest(fixture.writeApiEvidence(), "SUCCEEDED");
            assertEquals(3, fixture.writeApiEvidence().get("publicPostRequests")); stages.put("newAttempt", fixture.writeApiEvidence());
            fixture.setWriteApiAcl(false); ready(manifestPath, json, manifest, "READY_FOR_BROWSER_ACL_DENIAL");

            await(root, "denied", deadline); fixture.assertWriteApiCounts(3, 3);
            assertEquals(4, fixture.writeApiEvidence().get("publicPostRequests")); stages.put("aclDenied", fixture.writeApiEvidence());
            ready(manifestPath, json, manifest, "BROWSER_WRITE_API_VERIFIED"); await(root, "shutdown", deadline);
        } finally {
            manifest.remove("authStatePath"); if (Files.exists(manifestPath)) write(manifestPath, json, manifest); fixture.close();
        }
    }

    @SuppressWarnings("unchecked") private static void assertLatest(Map<String, Object> evidence, String status) {
        var ledger = (List<Map<String, Object>>) evidence.get("ledger"); assertEquals(status, ledger.get(ledger.size() - 1).get("status"));
        assertTrue(ledger.stream().allMatch(row -> "WRITE".equals(row.get("side_effect"))));
    }
    private static void await(Path root, String stage, long deadline) throws Exception {
        while (!Files.exists(root.resolve(stage + ".sentinel")) && !Files.exists(root.resolve("shutdown.sentinel"))
                && System.currentTimeMillis() < deadline) Thread.sleep(200);
        if (!Files.exists(root.resolve(stage + ".sentinel"))) throw new IllegalStateException("Missing actual browser write API stage: " + stage);
    }
    private static void ready(Path path, ObjectMapper json, Map<String, Object> manifest, String status) throws Exception {
        manifest.put("status", status); write(path, json, manifest); System.out.println("BMAPI_3B4_" + status);
    }
    private static void write(Path path, ObjectMapper json, Object value) throws Exception { Files.writeString(path, json.writeValueAsString(value), StandardCharsets.UTF_8); }
}
