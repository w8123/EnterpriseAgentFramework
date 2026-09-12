package com.enterprise.ai.pipeline.document.artifact;

import com.enterprise.ai.repository.DocumentArtifactLifecycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentArtifactReclaimer {
    private final DocumentArtifactBackend backend;
    private final DocumentArtifactLifecycleRepository artifacts;
    private final DocumentArtifactLifecycleStore lifecycle;

    @Value("${reachai.knowledge.artifact-cleanup.enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${reachai.knowledge.artifact-cleanup.interval-ms:10000}")
    public void reclaimPending() {
        if (!enabled) {
            return;
        }
        String storage = DocumentArtifactIdentity.storage(backend.storageId());
        for (var candidate : artifacts.findReclaimable(storage, 8)) {
            try {
                var claimed = lifecycle.claim(storage, candidate);
                if (claimed == null) {
                    continue;
                }
                String error = null;
                try {
                    backend.delete(claimed.getObjectKey());
                } catch (Exception failure) {
                    error = failure.getClass().getSimpleName();
                    if (error.length() > 128) {
                        error = error.substring(0, 128);
                    }
                }
                lifecycle.finish(claimed, error);
            } catch (Exception failure) {
                log.warn("工件回收暂未完成: artifactId={}, errorType={}",
                        candidate.getArtifactId(), failure.getClass().getSimpleName());
            }
        }
    }
}
