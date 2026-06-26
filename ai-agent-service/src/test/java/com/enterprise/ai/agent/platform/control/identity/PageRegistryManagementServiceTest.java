package com.enterprise.ai.agent.platform.control.identity;

import com.enterprise.ai.agent.assist.AiAccessSessionEntity;
import com.enterprise.ai.agent.assist.AiAccessSessionMapper;
import com.enterprise.ai.agent.assist.AiAccessStepEntity;
import com.enterprise.ai.agent.assist.AiAccessStepMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageRegistryManagementServiceTest {

    @Mock
    private PageRegistryMapper pageRegistryMapper;
    @Mock
    private PageActionRegistryMapper pageActionRegistryMapper;
    @Mock
    private EmbedSessionMapper embedSessionMapper;
    @Mock
    private PageActionEventMapper pageActionEventMapper;
    @Mock
    private EmbedChatEventMapper embedChatEventMapper;
    @Mock
    private AiAccessSessionMapper aiAccessSessionMapper;
    @Mock
    private AiAccessStepMapper aiAccessStepMapper;

    @InjectMocks
    private PageRegistryManagementService service;

    @Test
    void deletePageAndRelationsThrowsWhenPageMissing() {
        when(pageRegistryMapper.selectById(99L)).thenReturn(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.deletePageAndRelations(99L));

        assertEquals("frontend page not found: 99", error.getMessage());
        verify(pageActionRegistryMapper, never()).delete(any());
        verify(pageRegistryMapper, never()).delete(any());
    }

    @Test
    void deletePageAndRelationsRemovesPageAndDirectRelations() {
        PageRegistryEntity page = new PageRegistryEntity();
        page.setId(7L);
        page.setProjectCode("team-system");
        page.setPageKey("teamArchive.list");
        when(pageRegistryMapper.selectById(7L)).thenReturn(page);

        EmbedSessionEntity embedSession = new EmbedSessionEntity();
        embedSession.setSessionId("embed-1");
        when(embedSessionMapper.selectList(any())).thenReturn(List.of(embedSession));
        when(embedSessionMapper.delete(any())).thenReturn(1);
        when(pageActionEventMapper.delete(any())).thenReturn(2);
        when(embedChatEventMapper.delete(any())).thenReturn(3);
        when(pageActionRegistryMapper.delete(any())).thenReturn(4);

        AiAccessSessionEntity accessSession = new AiAccessSessionEntity();
        accessSession.setSessionId("access-1");
        when(aiAccessSessionMapper.selectList(any())).thenReturn(List.of(accessSession));
        when(aiAccessStepMapper.delete(any())).thenReturn(5);
        when(aiAccessSessionMapper.delete(any())).thenReturn(1);
        when(pageRegistryMapper.delete(any())).thenReturn(1);

        PageRegistryManagementService.PageRegistryDeleteResult result = service.deletePageAndRelations(7L);

        assertEquals(7L, result.pageId());
        assertEquals("team-system", result.projectCode());
        assertEquals("teamArchive.list", result.pageKey());
        assertEquals(1, result.deletedPages());
        assertEquals(4, result.deletedActions());
        assertEquals(1, result.deletedEmbedSessions());
        assertEquals(2, result.deletedPageActionEvents());
        assertEquals(3, result.deletedEmbedChatEvents());
        assertEquals(1, result.deletedAccessSessions());
        assertEquals(5, result.deletedAccessSteps());
    }
}
