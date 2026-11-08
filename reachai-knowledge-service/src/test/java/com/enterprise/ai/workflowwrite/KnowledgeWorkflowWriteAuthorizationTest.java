package com.enterprise.ai.workflowwrite;

import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.service.KnowledgeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KnowledgeWorkflowWriteAuthorizationTest {
    @Test
    void targetMustHaveMatchingProjectOwner() {
        var lookup = mock(KnowledgeBaseLookup.class);
        var service = mock(KnowledgeService.class);
        var controller = new KnowledgeWorkflowWriteInternalController(lookup, service);
        var kb = new KnowledgeBase();
        kb.setCode("kb");
        kb.setStatus(1);
        when(lookup.requireByCode("kb")).thenReturn(kb);
        var request = new KnowledgeWorkflowWriteInternalController.WriteTextRequest("kb", "text", "title", "project-a", "key");
        assertEquals(500, controller.writeText(request).getCode());
        kb.setProjectCode("project-b");
        assertEquals(500, controller.writeText(request).getCode());
        verifyNoInteractions(service);
        kb.setProjectCode("project-a");
        assertEquals(200, controller.writeText(request).getCode());
        verify(service).importChunks(any());
    }
}
