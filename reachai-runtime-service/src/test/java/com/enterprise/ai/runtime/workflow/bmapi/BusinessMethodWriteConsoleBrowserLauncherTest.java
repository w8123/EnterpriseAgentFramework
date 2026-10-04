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

/** Disposable real Console/SDK host; sentinels attest observed browser actions, never perform writes. */
class BusinessMethodWriteConsoleBrowserLauncherTest {
    @Test void servesConfirmedWriteUnknownQueriesFreshConfirmationAndAclDenial() throws Exception {
        String directory = System.getProperty("bmapi.writeBrowser.directory");
        assumeTrue(directory != null, "interactive WRITE browser fixture requires a fresh explicit directory");
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path manifestPath = root.resolve("write-console-browser-manifest.json");
        for (String name : List.of("write-console-browser-manifest.json", "cancel.sentinel", "success.sentinel",
                "unknown.sentinel", "queried.sentinel", "new-attempt-prepared.sentinel", "new-attempt.sentinel",
                "denied.sentinel", "shutdown.sentinel")) {
            if (Files.exists(root.resolve(name))) throw new IllegalStateException("Use a fresh WRITE browser directory; preserve prior evidence");
        }
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        var manifest = new LinkedHashMap<String, Object>();
        var stages = new LinkedHashMap<String, Object>();
        long deadline = System.currentTimeMillis() + 30 * 60_000L;
        try {
            fixture.startWriteFixture();
            var surface = fixture.prepareWriteBrowserSurface();
            manifest.put("controlOrigin", surface.controlOrigin()); manifest.put("projectId", surface.projectId());
            manifest.put("projectCode", surface.projectCode()); manifest.put("methodName", surface.methodName());
            manifest.put("qualifiedName", surface.qualifiedName()); manifest.put("authStatePath", surface.authStatePath());
            manifest.put("stages", stages);
            stages.put("initial", fixture.writeEvidence());
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_WRITE_CANCEL");

            await(root, "cancel", deadline); fixture.assertWriteCounts(0, 0);
            assertEquals(0, fixture.writeEvidence().get("publicPostRequests"));
            manifest.remove("authStatePath"); stages.put("cancel", fixture.writeEvidence());
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_WRITE_SUCCESS");

            await(root, "success", deadline); fixture.assertWriteCounts(1, 1);
            assertEquals(1, fixture.writeEvidence().get("publicPostRequests"));
            assertLatestStatus(fixture.writeEvidence(), "SUCCEEDED");
            assertEquals("bmapi2d", fixture.writeEvidence().get("signedProject"));
            assertNull(fixture.writeEvidence().get("signedTenant"));
            assertEquals(true, fixture.writeEvidence().get("businessUserAbsent"));
            assertEquals(true, fixture.writeEvidence().get("businessRolesAbsent"));
            stages.put("success", fixture.writeEvidence());
            fixture.dropNextWriteSdkResponse();
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_WRITE_UNKNOWN");

            await(root, "unknown", deadline); fixture.assertWriteCounts(2, 2);
            assertEquals(2, fixture.writeEvidence().get("publicPostRequests"));
            assertLatestStatus(fixture.writeEvidence(), "UNKNOWN");
            stages.put("unknown", fixture.writeEvidence());
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_WRITE_READ_ONLY_QUERY");

            await(root, "queried", deadline); fixture.assertWriteCounts(2, 2);
            assertEquals(2, fixture.writeEvidence().get("publicPostRequests"));
            assertTrue(((Number) fixture.writeEvidence().get("publicQueryRequests")).intValue() >= 2);
            assertLatestStatus(fixture.writeEvidence(), "UNKNOWN");
            stages.put("queriedAndReopened", fixture.writeEvidence());
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_WRITE_NEW_ATTEMPT");

            await(root, "new-attempt-prepared", deadline); fixture.assertWriteCounts(2, 2);
            assertEquals(2, fixture.writeEvidence().get("publicPostRequests"));
            stages.put("freshConfirmationBeforeSubmit", fixture.writeEvidence());
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_WRITE_FRESH_CONFIRMATION");

            await(root, "new-attempt", deadline); fixture.assertWriteCounts(3, 3);
            assertEquals(3, fixture.writeEvidence().get("publicPostRequests"));
            assertLatestStatus(fixture.writeEvidence(), "SUCCEEDED");
            stages.put("newAttempt", fixture.writeEvidence());
            fixture.setWriteAcl(false);
            ready(manifestPath, json, manifest, "READY_FOR_BROWSER_WRITE_ACL_DENIAL");

            await(root, "denied", deadline); fixture.assertWriteCounts(3, 3);
            assertEquals(4, fixture.writeEvidence().get("publicPostRequests"));
            stages.put("aclDenied", fixture.writeEvidence());
            ready(manifestPath, json, manifest, "BROWSER_WRITE_CONSOLE_VERIFIED");
            await(root, "shutdown", deadline);
        } finally {
            manifest.remove("authStatePath");
            if (Files.exists(manifestPath)) write(manifestPath, json, manifest);
            fixture.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertLatestStatus(Map<String, Object> evidence, String expected) {
        var ledger = (List<Map<String, Object>>) evidence.get("ledger");
        assertEquals(expected, ledger.get(ledger.size() - 1).get("status"));
    }

    private static void await(Path root, String stage, long deadline) throws Exception {
        while (!Files.exists(root.resolve(stage + ".sentinel")) && !Files.exists(root.resolve("shutdown.sentinel"))
                && System.currentTimeMillis() < deadline) Thread.sleep(200);
        if (!Files.exists(root.resolve(stage + ".sentinel"))) throw new IllegalStateException("Missing browser WRITE stage: " + stage);
    }

    private static void ready(Path path, ObjectMapper json, Map<String, Object> manifest, String status) throws Exception {
        manifest.put("status", status); write(path, json, manifest); System.out.println("BMAPI_2E_" + status);
    }

    private static void write(Path path, ObjectMapper json, Object value) throws Exception {
        Files.writeString(path, json.writeValueAsString(value), StandardCharsets.UTF_8);
    }
}
