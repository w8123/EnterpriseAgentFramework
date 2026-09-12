package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VectorStoreAttemptIsolationTest {
    @Test
    void failedUpsertRetainsAttemptIdsForExactCleanup() {
        var vectors = mock(VectorService.class);
        doThrow(new IllegalStateException("partial write")).when(vectors)
                .upsert(anyString(), anyList(), anyList(), anyList(), anyList());
        var context = context("attempt", "text");
        var executions = mock(DocumentIndexExecutionStore.class);
        when(executions.register(any(), any())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> new VectorStoreStep(vectors, executions).process(context));
        assertEquals(1, context.getVectorIds().size());
        verify(executions, never()).acknowledge(any());
    }

    @Test
    void lateOldWriteCannotOverwriteNewAttemptAndDuplicateCannotWriteAgain() {
        Map<String, String> stored = new HashMap<>();
        var vectors = mock(VectorService.class);
        doAnswer(call -> {
            List<String> ids = call.getArgument(1);
            List<String> content = call.getArgument(4);
            for (int i = 0; i < ids.size(); i++) stored.put(ids.get(i), content.get(i));
            return null;
        }).when(vectors).upsert(anyString(), anyList(), anyList(), anyList(), anyList());
        var executions = mock(DocumentIndexExecutionStore.class);
        when(executions.register(any(), any())).thenReturn(true, true, false);
        var step = new VectorStoreStep(vectors, executions);
        var current = context("current", "current text");
        var stale = context("stale", "stale text");
        step.process(current);
        step.process(stale);
        assertThrows(com.enterprise.ai.pipeline.PipelineException.class, () -> step.process(current));
        assertNotEquals(current.getVectorIds(), stale.getVectorIds());
        assertEquals(2, stored.size());
        assertEquals("current text", stored.get(current.getVectorIds().get(0)));
        assertTrue(current.getVectorIds().get(0).length() <= 128);
    }

    private static PipelineContext context(String lease, String content) {
        var context = new PipelineContext();
        context.setFileId("f".repeat(128));
        context.setImportJobId("job");
        context.setImportLeaseOwner(lease);
        context.setKnowledgeBaseCode("kb"); context.setKnowledgeBaseId(7L); context.setVectorCollectionName("kb");
        context.setChunks(List.of(content));
        context.setVectors(List.of(List.of(0.1f)));
        return context;
    }
}
