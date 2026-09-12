package com.enterprise.ai.runtime.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Rebuilds missing derived data at startup; each graph is read and replaced in its own locked transaction. */
@Component
@RequiredArgsConstructor
public class RuntimeWorkflowReferenceBootstrap {
    private final RuntimeWorkflowReferenceMapper references;
    private final RuntimeWorkflowReferenceIndex index;

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildMissing() {
        while (true) {
            var ids = references.missingDraftIds(200);
            if (ids.isEmpty()) break;
            ids.forEach(index::rebuildDraft);
        }
        while (true) {
            var ids = references.missingVersionIds(200);
            if (ids.isEmpty()) break;
            ids.forEach(index::rebuildVersion);
        }
    }
}
