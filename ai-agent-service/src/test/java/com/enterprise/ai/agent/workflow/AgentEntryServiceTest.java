package com.enterprise.ai.agent.workflow;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEntryServiceTest {

    @Test
    void createFillsDefaultsAndPersistsProjectEntryAgent() {
        AgentEntryMapper mapper = mock(AgentEntryMapper.class);
        when(mapper.insert(any(AgentEntryEntity.class))).thenReturn(1);
        AgentEntryService service = new AgentEntryService(mapper, new com.fasterxml.jackson.databind.ObjectMapper());

        AgentEntryEntity created = service.create(agent("project-entry"));

        assertEquals("PROJECT_ENTRY", created.getAgentKind());
        assertEquals("PROJECT", created.getVisibility());
        assertTrue(created.getEnabled());
        assertEquals(12, created.getId().length());

        ArgumentCaptor<AgentEntryEntity> captor = ArgumentCaptor.forClass(AgentEntryEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("project-entry", captor.getValue().getKeySlug());
        assertEquals("PROJECT_ENTRY", captor.getValue().getAgentKind());
    }

    @Test
    void createRejectsInvalidKeySlug() {
        AgentEntryService service = new AgentEntryService(mock(AgentEntryMapper.class), new com.fasterxml.jackson.databind.ObjectMapper());

        AgentEntryEntity request = agent("bad slug");

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    private AgentEntryEntity agent(String keySlug) {
        AgentEntryEntity entity = new AgentEntryEntity();
        entity.setProjectId(101L);
        entity.setProjectCode("demo");
        entity.setKeySlug(keySlug);
        entity.setName("Project Entry");
        return entity;
    }
}
