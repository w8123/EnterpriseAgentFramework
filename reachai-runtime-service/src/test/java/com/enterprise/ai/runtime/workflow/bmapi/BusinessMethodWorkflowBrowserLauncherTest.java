package com.enterprise.ai.runtime.workflow.bmapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

/**
 * Surefire entry point for the interactive browser gate.  It only delegates to
 * the existing fixture-backed launcher; the browser itself supplies the stop
 * sentinel after its real UI checks complete.
 */
@EnabledIfSystemProperty(named = "bmapi.browser.readyManifest", matches = ".+")
class BusinessMethodWorkflowBrowserLauncherTest {

    @Test
    void startsExistingFixtureForInteractiveBrowserGate() throws Exception {
        String readyManifest = System.getProperty("bmapi.browser.readyManifest");
        String shutdownSentinel = System.getProperty("bmapi.browser.shutdownSentinel");
        if (readyManifest == null || shutdownSentinel == null) {
            throw new IllegalStateException("browser ready-manifest and shutdown-sentinel system properties are required");
        }
        BusinessMethodWorkflowBrowserLauncher.run(Path.of(readyManifest), Path.of(shutdownSentinel));
    }
}
