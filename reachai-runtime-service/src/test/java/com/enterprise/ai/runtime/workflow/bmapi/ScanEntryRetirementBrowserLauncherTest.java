package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, disposable H2 + signed production boundary + real business HTTP browser gate. */
class ScanEntryRetirementBrowserLauncherTest {
    @Test
    void waitsForNormalOwnerDirectoriesSavedTrialAndExplicitPublication() throws Exception {
        String directory = System.getProperty("bmapi.retirementBrowser.directory");
        assertNotNull(directory, "fresh isolated browser evidence directory required");
        Path root = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
        Path manifest = root.resolve("retirement-browser-manifest.json");
        assertFalse(Files.exists(manifest), "never overwrite earlier evidence");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        long deadline = System.nanoTime() + Duration.ofMinutes(70).toNanos();
        try {
            fixture.start(); var surface = fixture.prepareRetirementBrowserSurface();
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("status", "READY_FOR_NORMAL_OWNER_FLOW"); state.put("controlOrigin", surface.controlOrigin());
            state.put("workflowId", surface.workflowId()); state.put("projectId", 41); state.put("projectCode", "orders");
            state.put("scanPath", surface.scanPath()); state.put("specFile", surface.specFile());
            state.put("upstreamOrigin", surface.upstreamOrigin()); state.put("authStatePath", surface.authStatePath());
            write(manifest, json, state); System.out.println("BMAPI_5A_BROWSER_READY");
            await(root, "trial", deadline); state.put("trialEvidence", fixture.verifyRetirementBrowserTrial());
            state.put("status", "WAITING_FOR_EXPLICIT_PUBLICATION"); write(manifest, json, state);
            System.out.println("BMAPI_5A_TRIAL_VERIFIED");
            await(root, "published", deadline); state.put("publicationEvidence", fixture.completeRetirementBrowserPublication());
            state.remove("authStatePath"); state.put("status", "NORMAL_OWNER_FLOW_VERIFIED"); write(manifest, json, state);
            System.out.println("BMAPI_5A_BROWSER_VERIFIED"); await(root, "shutdown", deadline);
        } finally { fixture.close(); }
    }
    private static void await(Path root, String name, long deadline) throws Exception {
        while (!Files.exists(root.resolve(name + ".sentinel"))) {
            if (!"shutdown".equals(name) && Files.exists(root.resolve("shutdown.sentinel"))) fail("browser gate cancelled");
            if (System.nanoTime() > deadline) fail("browser gate timed out at " + name);
            Thread.sleep(200L);
        }
    }
    private static void write(Path path, ObjectMapper json, Map<String, Object> state) throws Exception {
        Files.writeString(path, json.writeValueAsString(state));
    }
}
