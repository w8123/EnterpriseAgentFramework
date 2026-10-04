package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real browser gate; it never inserts a derived API, acceptance, Console proof or publication. */
class HttpApiMarketBindingBrowserLauncherTest {
    @Test void waitsForNormalMarketOwnerConsoleStudioAndPublishedWorkflow() throws Exception {
        String directory = System.getProperty("bmapi.marketBrowser.directory");
        Assumptions.assumeTrue(directory != null, "opt-in isolated browser gate");
        Path root = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
        Path manifest = root.resolve("market-browser-manifest.json"); assertFalse(Files.exists(manifest));
        var json = new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        long deadline = System.nanoTime() + Duration.ofMinutes(90).toNanos();
        try (var f = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            f.start(); var surface = f.prepareMarketBrowserSurface();
            Map<String, Object> state = new LinkedHashMap<>(); state.put("controlOrigin", surface.controlOrigin());
            state.put("projectId", 41); state.put("projectCode", "orders"); state.put("environment", "dev");
            state.put("entryKey", "isolated-orders-alpha"); state.put("versionId", 21); state.put("operationId", 31);
            state.put("upstreamOrigin", surface.upstreamOrigin()); state.put("authStatePath", surface.authStatePath());
            ready(manifest, json, state, "READY_FOR_MARKET_SELECTION");
            await(root, "integrated", deadline);
            var owner = f.marketDetail(1); assertNotNull(owner); assertEquals("DISCOVERED", owner.summary().sourceStatus());
            assertTrue(owner.summary().sourceConfirmed()); assertNull(owner.summary().acceptedContractHash());
            assertEquals(0, f.marketEvidence().get("upstreamRequests"));
            state.put("apiId", 1); state.put("qualifiedName", owner.summary().qualifiedName());
            state.put("afterIntegration", f.marketEvidence()); ready(manifest, json, state, "WAITING_FOR_ACCEPTANCE_AND_CONNECTION");
            await(root, "accepted-connected", deadline);
            assertEquals("ACCEPTED", f.marketDetail(1).summary().sourceStatus());
            assertEquals("CONFIGURED", f.marketConnection(1).get("status"));
            assertEquals("UNVERIFIED", proof(f).get("status")); assertEquals(0, f.marketEvidence().get("upstreamRequests"));
            // Explicit fixture-only target ACL after normal browser configuration; never a GLOBAL grant.
            f.grantApiTrialAcl(); state.put("configuredNotVerified", f.marketConnection(1));
            ready(manifest, json, state, "WAITING_FOR_ACTUAL_CONSOLE_GET");
            await(root, "console-called", deadline);
            assertEquals("VERIFIED", proof(f).get("status")); assertEquals(1, f.marketEvidence().get("upstreamRequests"));
            state.put("consoleProof", proof(f)); ready(manifest, json, state, "WAITING_FOR_STABLE_API_WORKFLOW_CREATION");
            await(root, "workflow-created", deadline);
            state.put("workflowId", f.marketBrowserAdoptCreatedWorkflow());
            ready(manifest, json, state, "WAITING_FOR_SAVED_DRAFT_AND_READONLY_TRIAL");
            await(root, "draft-tried", deadline);
            assertEquals(2, f.marketEvidence().get("upstreamRequests")); assertEquals("VERIFIED", proof(f).get("status"));
            state.put("trialRunTrace", f.marketBrowserTrialCompleted());
            state.put("afterDraftTrial", f.marketEvidence()); ready(manifest, json, state, "WAITING_FOR_EXPLICIT_WORKFLOW_PUBLICATION");
            await(root, "workflow-published", deadline);
            state.put("published", f.marketBrowserPublished());
            ready(manifest, json, state, "PUBLISHED_CALL_READY_FOR_BROWSER");
            await(root, "published-call-observed", deadline);
            f.changeReferenceUnavailable(true); ready(manifest, json, state, "REFERENCE_UNAVAILABLE_FOR_BROWSER");
            await(root, "unknown-references-observed", deadline);
            assertEquals("UNKNOWN", f.changeReferences().get("runtimeEvidence"));
            state.put("unknownReferences", f.changeReferences()); f.changeReferenceUnavailable(false);
            f.marketCatalogMutation("operation-off"); assertFalse(f.marketDetail(1).summary().sourceConfirmed());
            state.put("sourceUnavailableCode", f.marketRejectPublished());
            assertEquals(3, f.marketEvidence().get("upstreamRequests"));
            ready(manifest, json, state, "SOURCE_UNAVAILABLE_FOR_BROWSER");
            await(root, "source-unavailable-observed", deadline);
            state.put("unavailablePersistence", f.marketEvidence()); state.remove("authStatePath");
            ready(manifest, json, state, "BROWSER_MARKET_FLOW_VERIFIED");
            Files.writeString(root.resolve("browser-http-persistence-evidence.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            await(root, "shutdown", deadline);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> proof(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture f) throws Exception {
        return (Map<String, Object>)f.marketConnection(1).get("verification");
    }
    private static void ready(Path path, ObjectMapper json, Map<String, Object> state, String status) throws Exception {
        state.put("status", status); Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        System.out.println("BMAPI_3D_" + status);
    }
    private static void await(Path root, String name, long deadline) throws Exception {
        while (!Files.exists(root.resolve(name + ".sentinel"))) {
            if (!"shutdown".equals(name) && Files.exists(root.resolve("shutdown.sentinel"))) fail("browser gate cancelled");
            if (System.nanoTime() > deadline) fail("browser gate timed out at " + name);
            Thread.sleep(200L);
        }
    }
}
