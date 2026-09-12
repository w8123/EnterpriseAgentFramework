package com.enterprise.ai.runtime.workflow;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeWorkflowExecutionReaderTest {
    final RuntimeWorkflowDefinitionMapper workflows = mock(RuntimeWorkflowDefinitionMapper.class);
    final RuntimeWorkflowVersionMapper versions = mock(RuntimeWorkflowVersionMapper.class);
    final RuntimeWorkflowExecutionReader reader = new RuntimeWorkflowExecutionReader(workflows, versions);

    @Test void batchPreservesPinsAndReturnsValuesIndependentOfDatabaseRows() {
        var workflow = workflow(); var old = version(9L); var current = version(10L);
        old.setStatus("RETIRED");
        when(workflows.selectBatchIds(any())).thenReturn(List.of(workflow));
        when(versions.selectBatchIds(any())).thenReturn(List.of(old, current));
        var result = reader.resolve(List.of(new RuntimeWorkflowExecutionQuery.Reference("w1", 10L),
                new RuntimeWorkflowExecutionQuery.Reference("w1", 9L)));
        assertEquals(List.of(10L, 9L), result.stream().map(target -> target.version().getId()).toList());
        verify(workflows).selectBatchIds(List.of("w1")); verify(versions).selectBatchIds(List.of(10L, 9L));
        workflow.setName("later edit"); current.setGraphSpecSnapshotJson("invalid");
        assertEquals("published workflow", result.get(0).workflow().getName());
        assertEquals("{\"nodes\":[]}", result.get(0).version().getGraphSpecSnapshotJson());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test void draftOrWrongOwnerVersionCannotBeExecuted() {
        when(workflows.selectBatchIds(any())).thenReturn(List.of(workflow()));
        var version = version(9L); version.setStatus("DRAFT");
        when(versions.selectBatchIds(any())).thenReturn(List.of(version));
        var refs = List.of(new RuntimeWorkflowExecutionQuery.Reference("w1", 9L));
        assertThrows(IllegalStateException.class, () -> reader.resolve(refs));
        version.setStatus("ACTIVE"); version.setWorkflowId("another");
        assertThrows(IllegalStateException.class, () -> reader.resolve(refs));
    }

    private RuntimeWorkflowDefinitionEntity workflow() {
        var value = new RuntimeWorkflowDefinitionEntity(); value.setId("w1"); value.setName("published workflow"); value.setStatus("ACTIVE"); return value;
    }
    private RuntimeWorkflowVersionEntity version(Long id) {
        var value = new RuntimeWorkflowVersionEntity(); value.setId(id); value.setWorkflowId("w1"); value.setStatus("ACTIVE"); value.setGraphSpecSnapshotJson("{\"nodes\":[]}"); return value;
    }
}
