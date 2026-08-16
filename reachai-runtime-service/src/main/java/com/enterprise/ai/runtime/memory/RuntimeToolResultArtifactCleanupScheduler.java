package com.enterprise.ai.runtime.memory;

import com.enterprise.ai.runtime.supervisor.RuntimeContextEngineeringProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuntimeToolResultArtifactCleanupScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(RuntimeToolResultArtifactCleanupScheduler.class);

    private final RuntimeToolResultArtifactService artifactService;
    private final RuntimeContextEngineeringProperties properties;

    @Scheduled(
            fixedDelayString = "${reachai.runtime.context-engineering.artifact-cleanup-fixed-delay-ms:60000}",
            initialDelayString = "${reachai.runtime.context-engineering.artifact-cleanup-fixed-delay-ms:60000}")
    public void scrubExpiredCiphertext() {
        if (!properties.toolResultArtifactStoreConfigured()) {
            return;
        }
        try {
            int scrubbed = artifactService.scrubExpired();
            if (scrubbed > 0) {
                log.info("Scrubbed {} expired Runtime tool-result artifacts", scrubbed);
            }
        } catch (RuntimeException ex) {
            log.warn("Runtime tool-result artifact cleanup failed: {}", ex.getClass().getSimpleName());
        }
    }
}
