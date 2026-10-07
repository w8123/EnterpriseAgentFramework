package com.enterprise.ai.runtime.workflow.bmapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

@EnabledIfSystemProperty(named = "bmapi.apiBrowser.readyManifest", matches = ".+")
class BusinessMethodWorkflowApiBrowserLauncherTest {
    @Test
    void startsControllerApiFixtureForInteractiveBrowserGate() throws Exception {
        String ready = System.getProperty("bmapi.apiBrowser.readyManifest");
        String published = System.getProperty("bmapi.apiBrowser.publishedSentinel");
        String shutdown = System.getProperty("bmapi.apiBrowser.shutdownSentinel");
        if (ready == null || published == null || shutdown == null) {
            throw new IllegalStateException("API browser manifest and sentinels are required");
        }
        BusinessMethodWorkflowApiBrowserLauncher.run(Path.of(ready), Path.of(published), Path.of(shutdown));
    }
}
