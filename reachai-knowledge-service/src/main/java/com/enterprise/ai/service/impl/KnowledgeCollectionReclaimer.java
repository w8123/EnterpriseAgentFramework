package com.enterprise.ai.service.impl;

import com.enterprise.ai.repository.KnowledgeCollectionLifecycleRepository;
import com.enterprise.ai.vector.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeCollectionReclaimer {
    private final KnowledgeCollectionLifecycleRepository collections;
    private final KnowledgeCollectionLifecycleStore store;
    private final VectorService vectors;
    @Value("${reachai.knowledge.collection-cleanup.enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${reachai.knowledge.collection-cleanup.interval-ms:10000}")
    public void reclaimPending() {
        if (!enabled) {
            return;
        }
        for (var candidate : collections.findReclaimable(8)) {
            try {
                var claimed = store.claim(candidate);
                if (claimed == null) {
                    continue;
                }
                String error = null;
                try {
                    vectors.dropCollection(claimed.getCollectionName());
                } catch (Exception failure) {
                    error = failure.getClass().getSimpleName();
                    if (error.length() > 128) {
                        error = error.substring(0, 128);
                    }
                }
                store.finish(claimed, error);
            } catch (Exception failure) {
                log.warn("集合回收暂未完成: collection={}, errorType={}",
                        candidate.getCollectionName(), failure.getClass().getSimpleName());
            }
        }
    }
}
