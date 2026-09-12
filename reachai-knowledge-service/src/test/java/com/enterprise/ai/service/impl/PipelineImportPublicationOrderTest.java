package com.enterprise.ai.service.impl;

import com.enterprise.ai.pipeline.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PipelineImportPublicationOrderTest {
    @ParameterizedTest
    @ValueSource(strings = {"WORK,METADATA_PERSIST", "METADATA_PERSIST,WORK,METADATA_PERSIST", "METADATA_PERSIST,WORK", "WORK", ""})
    void validatesPublicationBeforeExecutingAnyStep(String names) {
        var executed = new ArrayList<String>();
        var pipeline = new KnowledgeImportPipeline("kb");
        if (!names.isEmpty()) for (String name : names.split(",")) {
            pipeline.addStep(new PipelineStep() {
                public String getName() { return name; }
                public void process(PipelineContext context) {
                    executed.add(name);
                    if ("METADATA_PERSIST".equals(name)) context.setImportPublished(true);
                }
            });
        }
        var factory = mock(PipelineFactory.class);
        when(factory.create("kb")).thenReturn(pipeline);
        var context = new PipelineContext();
        context.setKnowledgeBaseCode("kb"); context.setFileId("file"); context.setImportJobId("job");
        var lookup = mock(com.enterprise.ai.repository.KnowledgeBaseLookup.class);
        var kb = new com.enterprise.ai.domain.entity.KnowledgeBase();
        kb.setId(7L); kb.setCode("kb"); kb.setVectorCollectionName("physical-kb");
        context.setKnowledgeBaseId(7L); context.setVectorCollectionName("physical-kb");
        when(lookup.requireById(7L)).thenReturn(kb);
        var result = new PipelineImportServiceImpl(factory, lookup, mock(com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore.class)).execute(context);
        if (names.equals("WORK,METADATA_PERSIST")) {
            assertEquals("SUCCESS", result.getStatus());
            assertEquals(List.of("WORK", "METADATA_PERSIST"), executed);
        } else {
            assertEquals("FAILED", result.getStatus());
            assertTrue(executed.isEmpty());
        }
    }
}
