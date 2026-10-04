package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real browser gate; browser mutations use the production Control/owner/signed Runtime paths. */
class HttpApiChangeImpactBrowserLauncherTest {
    @Test void waitsForNormalChangeImpactBrowserFlow() throws Exception {
        String directory = System.getProperty("bmapi.changeImpactBrowser.directory");
        assertNotNull(directory, "a fresh isolated browser evidence directory is required");
        Path root = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
        Path manifest = root.resolve("change-impact-browser-manifest.json"); assertFalse(Files.exists(manifest));
        var json = new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        long deadline = System.nanoTime() + Duration.ofMinutes(90).toNanos();
        try (var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            fixture.start(); var surface = fixture.prepareChangeImpactSurface();
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("controlOrigin", surface.controlOrigin()); state.put("workflowId", surface.workflowId());
            state.put("projectId", 41); state.put("projectCode", "orders"); state.put("publicationId", fixture.changePublicationId());
            state.put("scanPath", surface.scanPath()); state.put("specFile", surface.specFile());
            state.put("upstreamOrigin", surface.upstreamOrigin()); state.put("authStatePath", surface.authStatePath());
            state.put("initialEvidence", fixture.multiSourceEvidence()); ready(manifest, json, state, "READY_FOR_NORMAL_SCANS_AND_PUBLICATION");
            await(root, "initial-published", deadline);
            var accepted = fixture.multiSourceDetail(); assertEquals("ACCEPTED", accepted.summary().sourceStatus());
            assertEquals(2, accepted.summary().activeSourceCount()); fixture.assertMultiSourceWorkflowSelection();
            state.put("initialCall", fixture.changeInitialPublished()); state.put("firstPublishedEvidence", fixture.changeEvidence());
            fixture.changeSources("display"); ready(manifest, json, state, "DISPLAY_SOURCE_READY");
            await(root, "display-scanned", deadline);
            var display = fixture.multiSourceDetail(); assertEquals("ACCEPTED", display.summary().sourceStatus());
            assertEquals(accepted.summary().acceptedContractHash(), display.summary().candidateContractHash());
            assertNotEquals(accepted.summary().sourceSetRevision(), display.summary().sourceSetRevision());
            state.put("displayOldCallCode", fixture.changeRejectPublished()); fixture.changeAssertOriginalUntouched(true);
            state.put("displayEvidence", fixture.changeEvidence());
            fixture.changeSources("required"); ready(manifest, json, state, "OPENAPI_CONTRACT_SOURCE_READY");
            await(root, "conflict-scanned", deadline);
            assertEquals("CONFLICT", fixture.multiSourceDetail().summary().sourceStatus()); fixture.changeRejectPublished();
            state.put("conflictEvidence", fixture.changeEvidence()); ready(manifest, json, state, "CONTROLLER_CONTRACT_SOURCE_READY");
            await(root, "contract-scanned", deadline);
            assertEquals("CONTRACT_DRIFT", fixture.multiSourceDetail().summary().sourceStatus()); fixture.changeRejectPublished();
            state.put("driftEvidence", fixture.changeEvidence()); ready(manifest, json, state, "WAITING_FOR_OWNER_ACCEPTANCE");
            await(root, "accepted", deadline);
            assertEquals("ACCEPTED", fixture.multiSourceDetail().summary().sourceStatus()); fixture.changeAssertOriginalUntouched(true);
            state.put("acceptedOldCallCode", fixture.changeRejectPublished()); state.put("acceptedEvidence", fixture.changeEvidence());
            ready(manifest, json, state, "WAITING_FOR_EXPLICIT_WORKFLOW_RELEASE");
            await(root, "workflow-republished", deadline);
            fixture.changeAssertOriginalUntouched(true); state.put("newWorkflowOldMcpCode", fixture.changeRejectPublished());
            state.put("newWorkflowOldMcpEvidence", fixture.changeEvidence()); ready(manifest, json, state, "WAITING_FOR_EXPLICIT_MCP_UPDATE");
            await(root, "mcp-republished", deadline);
            state.put("newCall", fixture.changeCall()); state.put("originalVersionAfterUpdateCode", fixture.changeRejectOriginalAfterUpdate());
            fixture.changeAssertOriginalUntouched(false); state.put("newPublishedEvidence", fixture.changeEvidence());
            fixture.changeReferenceUnavailable(true); ready(manifest, json, state, "REFERENCE_UNAVAILABLE_FOR_BROWSER");
            await(root, "reference-observed", deadline);
            assertEquals("UNKNOWN", fixture.changeReferences().get("runtimeEvidence")); state.put("unavailableReferences", fixture.changeReferences());
            fixture.changeReferenceUnavailable(false); ready(manifest, json, state, "REFERENCE_RECOVERY_READY");
            await(root, "reference-recovered", deadline);
            assertEquals("COMPLETE", fixture.changeReferences().get("runtimeEvidence")); state.put("recoveredReferences", fixture.changeReferences());
            // New normal HTTP/persistence evidence for remaining inventory guards; the browser then reads the actual missing state.
            HttpApiChangeImpactE2eIntegrationTest.verifySourceRemovalAndIdentity(fixture, state);
            ready(manifest, json, state, "OLD_SOURCE_MISSING_FOR_BROWSER");
            await(root, "missing-observed", deadline);
            state.remove("authStatePath"); ready(manifest, json, state, "BROWSER_CHANGE_IMPACT_VERIFIED");
            await(root, "shutdown", deadline);
        }
    }
    private static void ready(Path path, ObjectMapper json, Map<String, Object> state, String status) throws Exception {
        state.put("status", status); Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        System.out.println("BMAPI_4A_" + status);
    }
    private static void await(Path root, String name, long deadline) throws Exception {
        while (!Files.exists(root.resolve(name + ".sentinel"))) {
            if (!"shutdown".equals(name) && Files.exists(root.resolve("shutdown.sentinel"))) fail("browser gate cancelled");
            if (System.nanoTime() > deadline) fail("browser gate timed out at " + name);
            Thread.sleep(200L);
        }
    }
}
