package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Fresh production classes for the same browser reconciliation error, without replaying publication. */
class ScanEntryReconcileBrowserLauncherTest {
    @Test
    void verifiesReadOnlyBrowserReconciliationAfterNormalDiscovery() throws Exception {
        String directory = System.getProperty("bmapi.reconcileBrowser.directory");
        assumeTrue(directory != null, "opt-in disposable browser gate");
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path manifest = root.resolve("reconcile-browser-manifest.json");
        assertFalse(Files.exists(manifest), "never overwrite previous browser evidence");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        long deadline = System.nanoTime() + Duration.ofMinutes(12).toNanos();
        try {
            fixture.start();
            var surface = fixture.prepareMultiSourceBrowserSurface();
            assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
            fixture.selectMultiSourceOpenApi();
            assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
            var before = fixture.readOnlySourceSnapshot();
            assertEquals(2, before.get("capability_scan_project_tool").size());
            assertTrue(before.get("capability_scan_project_tool").stream()
                    .allMatch(row -> row.get("global_tool_definition_id") == null));
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("status", "READY_FOR_READ_ONLY_RECONCILE");
            state.put("controlOrigin", surface.controlOrigin());
            state.put("authStatePath", surface.authStatePath());
            state.put("scanPath", surface.scanPath());
            Files.writeString(manifest, json.writeValueAsString(state));
            System.out.println("BMAPI_5A_RECONCILE_BROWSER_READY");
            await(root, "reconciled", deadline);
            assertEquals(1, fixture.readOnlyReconcileRequestCount());
            assertEquals(before, fixture.readOnlySourceSnapshot(), "all source/owner/projection/ledger/version facts remain unchanged");
            assertEquals(0, fixture.retiredEntryRequestCount());
            state.remove("authStatePath");
            state.put("status", "READ_ONLY_RECONCILE_VERIFIED");
            state.put("sourceRows", 2);
            state.put("unchangedTables", before.size());
            state.put("toolProjectionCount", before.get("capability_tool_definition").size());
            state.put("consoleLedgerCount", before.get("runtime_console_capability_invocation").size());
            state.put("retiredEntryRequests", 0);
            Files.writeString(manifest, json.writeValueAsString(state));
            System.out.println("BMAPI_5A_RECONCILE_BROWSER_VERIFIED");
            await(root, "shutdown", deadline);
        } finally {
            fixture.close();
        }
    }

    private static void await(Path root, String name, long deadline) throws Exception {
        while (!Files.exists(root.resolve(name + ".sentinel"))) {
            if (System.nanoTime() > deadline) fail("reconcile browser timed out at " + name);
            Thread.sleep(200L);
        }
    }
}
