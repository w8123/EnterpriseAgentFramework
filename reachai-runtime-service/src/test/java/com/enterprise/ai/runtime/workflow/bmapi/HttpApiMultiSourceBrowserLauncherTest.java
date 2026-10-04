package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in browser gate: normal public project scans, owner facts, signed Console/Runtime and real MVC GET. */
class HttpApiMultiSourceBrowserLauncherTest {
    @Test
    void waitsForNormalDualSourceBrowserFlowAndConflictRescans() throws Exception {
        String directory = System.getProperty("bmapi.multiSourceBrowser.directory");
        assertNotNull(directory, "an isolated browser evidence directory is required");
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path manifest = root.resolve("multi-source-browser-manifest.json");
        assertFalse(Files.exists(manifest), "use a fresh directory; never overwrite earlier evidence");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        long deadline = System.nanoTime() + Duration.ofMinutes(60).toNanos();
        try {
            fixture.start();
            var surface = fixture.prepareMultiSourceBrowserSurface();
            fixture.grantExpectedMultiSourceBrowserAcl(); // explicit synthetic authorization, before any discovery
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("status", "READY_FOR_NORMAL_SCANS"); state.put("controlOrigin", surface.controlOrigin());
            state.put("workflowId", surface.workflowId()); state.put("projectId", 41); state.put("projectCode", "orders");
            state.put("scanPath", surface.scanPath()); state.put("specFile", surface.specFile());
            state.put("upstreamOrigin", surface.upstreamOrigin()); state.put("authStatePath", surface.authStatePath());
            state.put("initialEvidence", fixture.multiSourceEvidence());
            write(manifest, json, state); System.out.println("BMAPI_3B3_BROWSER_READY");
            await(root, "success", deadline);
            var successful = fixture.multiSourceEvidence();
            var owner = fixture.multiSourceDetail();
            assertEquals("ACCEPTED", owner.summary().sourceStatus()); assertEquals(2, owner.summary().activeSourceCount());
            assertEquals(1, successful.get("assetCount")); assertEquals(2, successful.get("bindingCount"));
            assertEquals(1, successful.get("attemptCount")); assertEquals(1, successful.get("controllerMethodCalls"));
            assertEquals(1, successful.get("upstreamRequests"));
            fixture.assertMultiSourceWorkflowSelection();
            state.put("successEvidence", successful);
            fixture.changeMultiSourceSpec("conflict");
            state.put("status", "WAITING_FOR_NORMAL_CONFLICT_RESCAN"); write(manifest, json, state);
            System.out.println("BMAPI_3B3_CONFLICT_SOURCE_READY");
            await(root, "conflict", deadline);
            var conflict = fixture.multiSourceDetail(); assertEquals("CONFLICT", conflict.summary().sourceStatus());
            assertEquals(owner.summary().acceptedContractHash(), conflict.summary().acceptedContractHash());
            assertEquals(409, fixture.multiSourcePublicRequest("POST", "/api/apis/1/invocations",
                    fixture.multiSourceInvocationBody(owner.summary().acceptedContractHash())).statusCode());
            state.put("conflictEvidence", fixture.multiSourceEvidence());
            fixture.changeMultiSourceSpec("unknown");
            state.put("status", "WAITING_FOR_NORMAL_UNKNOWN_RESCAN"); write(manifest, json, state);
            System.out.println("BMAPI_3B3_UNKNOWN_SOURCE_READY");
            await(root, "unknown", deadline);
            var unknown = fixture.multiSourceDetail();
            // Partial coverage retains the last observed conflict; it cannot erase either known fact.
            assertEquals("CONFLICT", unknown.summary().sourceStatus());
            assertFalse(unknown.summary().sourceConfirmed());
            assertTrue(unknown.sources().stream().anyMatch(source -> "OPENAPI_SCAN".equals(source.sourceKind())
                    && !source.inventoryComplete() && !source.confirmedInLatestInventory()));
            assertEquals(2, unknown.summary().activeSourceCount());
            var finalEvidence = fixture.multiSourceEvidence();
            assertEquals(1, finalEvidence.get("attemptCount")); assertEquals(1, finalEvidence.get("upstreamRequests"));
            state.put("unknownEvidence", finalEvidence); state.remove("authStatePath");
            state.put("status", "BROWSER_MULTI_SOURCE_CONFLICT_UNKNOWN_VERIFIED"); write(manifest, json, state);
            System.out.println("BMAPI_3B3_BROWSER_VERIFIED");
            await(root, "shutdown", deadline);
        } finally {
            fixture.close();
        }
    }

    private static void await(Path root, String name, long deadline) throws Exception {
        Path signal = root.resolve(name + ".sentinel");
        while (!Files.exists(signal)) {
            if (!"shutdown".equals(name) && Files.exists(root.resolve("shutdown.sentinel"))) fail("browser gate cancelled");
            if (System.nanoTime() > deadline) fail("browser gate timed out at " + name);
            Thread.sleep(200L);
        }
    }
    private static void write(Path path, ObjectMapper json, Map<String, Object> value) throws Exception {
        Files.writeString(path, json.writeValueAsString(value));
    }
}
