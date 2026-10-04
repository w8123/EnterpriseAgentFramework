package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Vite/Control route gate against disposable H2 and the production SDK endpoint. */
class BusinessMethodReadOnlyTrialBrowserLauncherTest {
    @Test void servesSavedMethodTrialAndExactAclDenialForTheRealBrowser() throws Exception {
        String directory = System.getProperty("bmapi.methodTrialBrowser.directory");
        assumeTrue(directory != null, "interactive browser fixture requires an explicit fresh output directory");
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path manifestPath = root.resolve("method-trial-browser-manifest.json");
        Path success = root.resolve("success.sentinel"), denied = root.resolve("denied.sentinel");
        Path shutdown = root.resolve("shutdown.sentinel");
        if (Files.exists(manifestPath) || Files.exists(success) || Files.exists(denied) || Files.exists(shutdown)) {
            throw new IllegalStateException("Use a fresh method browser directory; prior evidence is preserved");
        }
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        try {
            fixture.start();
            var surface = fixture.prepareMethodBrowserSurface();
            fixture.grantTrialPermissions(); fixture.grantMethodTrialAcl();
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("status", "READY_FOR_BROWSER_METHOD_TRIAL");
            manifest.put("controlOrigin", surface.controlOrigin()); manifest.put("workflowId", surface.workflowId());
            manifest.put("projectId", surface.projectId()); manifest.put("projectCode", surface.projectCode());
            manifest.put("methodName", surface.methodName()); manifest.put("qualifiedName", surface.qualifiedName());
            manifest.put("authStatePath", surface.authStatePath()); write(manifestPath, json, manifest);
            System.out.println("BMAPI_METHOD_TRIAL_BROWSER_READY");
            long deadline = System.currentTimeMillis() + 20 * 60_000L;
            while (!Files.exists(success) && !Files.exists(shutdown) && System.currentTimeMillis() < deadline) Thread.sleep(200);
            if (!Files.exists(success)) throw new IllegalStateException("Browser method success evidence not supplied");
            manifest.remove("authStatePath"); manifest.putAll(fixture.completeMethodBrowserTrial(surface));
            fixture.revokeMethodTrialAcl(); manifest.put("status", "READY_FOR_BROWSER_METHOD_ACL_DENIAL");
            write(manifestPath, json, manifest); System.out.println("BMAPI_METHOD_TRIAL_BROWSER_DENIAL_READY");
            while (!Files.exists(denied) && !Files.exists(shutdown) && System.currentTimeMillis() < deadline) Thread.sleep(200);
            if (!Files.exists(denied)) throw new IllegalStateException("Browser method denial evidence not supplied");
            fixture.assertMethodBrowserTrialDeniedWithoutAnotherDispatch(surface);
            manifest.put("status", "BROWSER_METHOD_TRIAL_AND_ACL_DENIAL_VERIFIED");
            write(manifestPath, json, manifest); System.out.println("BMAPI_METHOD_TRIAL_BROWSER_VERIFIED");
            while (!Files.exists(shutdown) && System.currentTimeMillis() < deadline) Thread.sleep(200);
        } finally { fixture.close(); }
    }
    private static void write(Path path, ObjectMapper json, Object value) throws Exception {
        Files.writeString(path, json.writeValueAsString(value), StandardCharsets.UTF_8);
    }
}
