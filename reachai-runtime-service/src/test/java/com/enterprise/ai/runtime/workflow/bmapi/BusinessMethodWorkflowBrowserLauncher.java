package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts the existing BMAPI-2D-B fixture for a real-browser check without
 * duplicating its SDK registration, Control/Runtime/Capability bridges, or
 * publication flow.  The ready manifest deliberately exposes no credential,
 * token, cookie, signature, or H2 connection detail.
 */
public final class BusinessMethodWorkflowBrowserLauncher {

    private BusinessMethodWorkflowBrowserLauncher() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected ready-manifest and shutdown-sentinel paths");
        }
        run(Path.of(arguments[0]), Path.of(arguments[1]));
    }

    static void run(Path readyManifestPath, Path shutdownSentinelPath) throws Exception {
        Path readyManifest = readyManifestPath.toAbsolutePath().normalize();
        Path shutdownSentinel = shutdownSentinelPath.toAbsolutePath().normalize();
        Files.createDirectories(readyManifest.getParent());
        Files.deleteIfExists(readyManifest);

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture =
                new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        try {
            fixture.start();
            BusinessMethodWorkflowMcpE2eIntegrationTest.BrowserSurface surface = fixture.prepareBrowserSurface();
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("controlOrigin", surface.controlOrigin());
            manifest.put("workflowId", surface.workflowId());
            manifest.put("projectId", surface.projectId());
            manifest.put("projectCode", surface.projectCode());
            manifest.put("qualifiedNames", surface.qualifiedNames());
            manifest.put("isolation", "same-JVM loopback bridges with one ephemeral H2 fixture");
            Files.writeString(readyManifest, json.writeValueAsString(manifest), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            System.out.println("BMAPI_BROWSER_READY");
            while (!Files.exists(shutdownSentinel)) {
                Thread.sleep(200L);
            }
        } finally {
            Files.deleteIfExists(readyManifest);
            Files.deleteIfExists(shutdownSentinel);
            fixture.close();
        }
    }
}
