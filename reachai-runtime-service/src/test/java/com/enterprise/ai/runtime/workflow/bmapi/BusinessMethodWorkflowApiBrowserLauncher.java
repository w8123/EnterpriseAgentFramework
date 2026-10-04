package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Interactive, isolated API Workflow browser gate using the production Control and Runtime controllers. */
public final class BusinessMethodWorkflowApiBrowserLauncher {
    private BusinessMethodWorkflowApiBrowserLauncher() {
    }

    static void run(Path readyPath, Path publishedPath, Path shutdownPath) throws Exception {
        Path ready = readyPath.toAbsolutePath().normalize();
        Path published = publishedPath.toAbsolutePath().normalize();
        Path shutdown = shutdownPath.toAbsolutePath().normalize();
        Files.createDirectories(ready.getParent());
        Files.deleteIfExists(ready);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture =
                new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json);
        try {
            fixture.start();
            var surface = fixture.prepareApiBrowserSurface();
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("status", "READY_FOR_BROWSER_PUBLISH");
            manifest.put("controlOrigin", surface.controlOrigin());
            manifest.put("workflowId", surface.workflowId());
            manifest.put("projectId", surface.projectId());
            manifest.put("projectCode", surface.projectCode());
            manifest.put("apiId", surface.apiId());
            manifest.put("authStatePath", surface.authStatePath());
            write(ready, json, manifest);
            System.out.println("BMAPI_API_BROWSER_READY");
            while (!Files.exists(published) && !Files.exists(shutdown)) Thread.sleep(200L);
            if (Files.exists(published)) {
                var outcome = fixture.completeApiBrowserPublish(surface);
                manifest.remove("authStatePath");
                manifest.put("status", "BROWSER_PUBLISHED_AND_EXECUTED");
                manifest.put("versionId", outcome.versionId());
                manifest.put("version", outcome.version());
                manifest.put("runId", outcome.runId());
                manifest.put("traceId", outcome.traceId());
                manifest.put("controllerMethodCalls", outcome.controllerMethodCalls());
                write(ready, json, manifest);
                System.out.println("BMAPI_API_BROWSER_OUTCOME");
                while (!Files.exists(shutdown)) Thread.sleep(200L);
            }
        } finally {
            Files.deleteIfExists(published);
            Files.deleteIfExists(shutdown);
            fixture.close();
        }
    }

    private static void write(Path path, ObjectMapper json, Map<String, Object> manifest) throws Exception {
        Files.writeString(path, json.writeValueAsString(manifest), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
