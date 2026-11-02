package com.enterprise.ai.controller;

import com.enterprise.ai.config.KnowledgeDependencyInfoContributor;
import com.enterprise.ai.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeLegacySettingsWriteTest {
    @Test void obsoleteOwnerRoutesCannotBypassControlOrAcquireAFreshRevision() throws Exception {
        var knowledge = mock(KnowledgeService.class);
        var dependencies = mock(KnowledgeDependencyInfoContributor.class);
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(knowledge, dependencies)).build();
        for (String path : List.of("/knowledge/base", "/knowledge/kb/kb/config")) {
            mvc.perform(put(path).contentType("application/json").content("{\"code\":\"kb\",\"name\":\"stale\",\"accessMode\":\"ALL_CALLERS\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KNOWLEDGE_SETTINGS_REVISION_REQUIRED"))
                    .andExpect(jsonPath("$.savePath").value("/ai/knowledge/kb/{kbCode}/settings-if-unchanged"));
            mvc.perform(put(path).contentType("application/json").content("invalid-json"))
                    .andExpect(status().isConflict());
        }
        verifyNoInteractions(knowledge, dependencies);
    }

    @Test void creationKeepsItsExistingPostContractWhileOldPutIsClosed() throws Exception {
        var knowledge = mock(KnowledgeService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(knowledge, mock(KnowledgeDependencyInfoContributor.class))).build();
        mvc.perform(post("/knowledge/base").contentType("application/json")
                        .content("{\"code\":\"kb\",\"name\":\"new\",\"embeddingModelInstanceId\":\"embedding\",\"llmModelInstanceId\":\"llm\"}")
                        .accept("application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(knowledge).create(any());
    }
}
