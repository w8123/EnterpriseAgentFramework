package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Isolated H2 and production route gate; the browser must save and submit through Vite/Control. */
class WorkflowReadOnlyTrialBrowserLauncherTest {
    @Test void servesSavedDraftTrialAndAclDenialForTheRealBrowser() throws Exception {
        String directory = System.getProperty("bmapi.trialBrowser.directory");
        assumeTrue(directory != null, "interactive browser fixture requires an explicit output directory");
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path ready = root.resolve("trial-browser-manifest.json");
        Path success = root.resolve("success.sentinel");
        Path denied = root.resolve("denied.sentinel");
        Path shutdown = root.resolve("shutdown.sentinel");
        if (Files.exists(ready) || Files.exists(success) || Files.exists(denied) || Files.exists(shutdown)) {
            throw new IllegalStateException("Use a fresh trial browser directory; prior evidence is preserved");
        }
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        try {
            fixture.start();
            var surface = fixture.prepareApiBrowserSurface();
            fixture.grantTrialPermissions(); fixture.grantApiTrialAcl();
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("status", "READY_FOR_BROWSER_TRIAL"); manifest.put("controlOrigin", surface.controlOrigin());
            manifest.put("workflowId", surface.workflowId()); manifest.put("projectId", surface.projectId());
            manifest.put("projectCode", surface.projectCode()); manifest.put("apiId", surface.apiId());
            manifest.put("authStatePath", surface.authStatePath());
            write(ready, json, manifest);
            System.out.println("BMAPI_TRIAL_BROWSER_READY");
            long deadline = System.currentTimeMillis() + 20 * 60_000L;
            while (!Files.exists(success) && !Files.exists(shutdown) && System.currentTimeMillis() < deadline) Thread.sleep(200);
            if (!Files.exists(success)) throw new IllegalStateException("Browser success evidence not supplied");
            manifest.remove("authStatePath"); manifest.putAll(fixture.completeApiBrowserTrial(surface));
            fixture.revokeApiTrialAcl(); manifest.put("status", "READY_FOR_BROWSER_ACL_DENIAL");
            write(ready, json, manifest); System.out.println("BMAPI_TRIAL_BROWSER_DENIAL_READY");
            while (!Files.exists(denied) && !Files.exists(shutdown) && System.currentTimeMillis() < deadline) Thread.sleep(200);
            if (!Files.exists(denied)) throw new IllegalStateException("Browser denial evidence not supplied");
            fixture.assertBrowserTrialDeniedWithoutAnotherDispatch(surface);
            manifest.put("status", "BROWSER_TRIAL_AND_ACL_DENIAL_VERIFIED");
            write(ready, json, manifest); System.out.println("BMAPI_TRIAL_BROWSER_VERIFIED");
            while (!Files.exists(shutdown) && System.currentTimeMillis() < deadline) Thread.sleep(200);
        } finally { fixture.close(); }
    }
    private static void write(Path path, ObjectMapper json, Object value) throws Exception {
        Files.writeString(path, json.writeValueAsString(value), StandardCharsets.UTF_8);
    }
}
