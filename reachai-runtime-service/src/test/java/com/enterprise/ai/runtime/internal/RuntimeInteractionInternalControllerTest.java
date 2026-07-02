package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.interaction.RuntimeSkillInteractionEntity;
import com.enterprise.ai.runtime.interaction.RuntimeSkillInteractionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeInteractionInternalControllerTest {

    @Test
    void cancelsAdminTestPendingInteraction() {
        RuntimeSkillInteractionMapper mapper = mock(RuntimeSkillInteractionMapper.class);
        RuntimeSkillInteractionEntity row = new RuntimeSkillInteractionEntity();
        row.setId("ix-1");
        row.setUserId(RuntimeInteractionInternalController.COMPOSITION_TEST_SESSION_ID);
        row.setStatus("PENDING");
        when(mapper.selectById("ix-1")).thenReturn(row);

        RuntimeInteractionInternalController controller = new RuntimeInteractionInternalController(mapper);

        ResponseEntity<RuntimeInteractionInternalController.CancelResult> response =
                controller.cancelAdminTestInteraction("ix-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("CANCELLED", response.getBody().status());
        assertEquals("CANCELLED", row.getStatus());
        verify(mapper).updateById(row);
    }

    @Test
    void listsPendingAdminTestInteractions() {
        RuntimeSkillInteractionMapper mapper = mock(RuntimeSkillInteractionMapper.class);
        RuntimeSkillInteractionEntity row = new RuntimeSkillInteractionEntity();
        row.setId("ix-1");
        row.setUserId(RuntimeInteractionInternalController.COMPOSITION_TEST_SESSION_ID);
        row.setStatus("PENDING");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        RuntimeInteractionInternalController controller = new RuntimeInteractionInternalController(mapper);

        ResponseEntity<List<RuntimeInteractionInternalController.SkillInteractionRecord>> response =
                controller.listPendingAdminTestInteractions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ix-1", response.getBody().get(0).id());
    }
}
