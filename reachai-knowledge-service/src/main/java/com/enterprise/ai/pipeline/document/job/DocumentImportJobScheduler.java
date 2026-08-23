package com.enterprise.ai.pipeline.document.job;

import com.enterprise.ai.service.DocumentImportJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers queued/retryable jobs after caller disconnects or a pod restarts. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentImportJobScheduler {

    private final DocumentImportJobService documentImportJobService;
    private final DocumentImportJobProperties properties;

    @Scheduled(fixedDelayString = "${reachai.knowledge.document-import-worker.poll-interval-ms:5000}")
    public void dispatchPendingJobs() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            documentImportJobService.dispatchPending();
        } catch (Exception e) {
            log.error("文档导入任务调度失败", e);
        }
    }
}
