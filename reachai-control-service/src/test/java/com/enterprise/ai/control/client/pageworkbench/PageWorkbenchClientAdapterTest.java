package com.enterprise.ai.control.client.pageworkbench;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageWorkbenchClientAdapterTest {

    @Test
    void delegatesProjectModelAndRuntimeCallsWithoutChangingResponses() {
        CapabilityProjectOnboardingClient capabilityClient =
                mock(CapabilityProjectOnboardingClient.class);
        ControlModelCatalogClient modelClient =
                mock(ControlModelCatalogClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        Map<String, Object> project = Map.of("projectCode", "orders");
        List<Map<String, Object>> tools =
                List.of(Map.of("qualifiedName", "orders.query"));
        ResponseEntity<Map<String, Object>> model =
                ResponseEntity.ok(Map.of("id", "model-orders"));
        ResponseEntity<Object> nodeTypes =
                ResponseEntity.ok(List.of(Map.of("type", "TOOL")));
        when(capabilityClient.getProjectById(7L)).thenReturn(project);
        when(capabilityClient.listProjectTools(7L)).thenReturn(tools);
        when(modelClient.getInternal("model-orders")).thenReturn(model);
        when(runtimeClient.pageWorkbenchWorkflowNodeTypes())
                .thenReturn(nodeTypes);
        PageWorkbenchClientAdapter adapter = new PageWorkbenchClientAdapter(
                capabilityClient,
                modelClient,
                runtimeClient);

        assertSame(project, adapter.getProjectById(7L));
        assertSame(tools, adapter.listProjectTools(7L));
        assertSame(model, adapter.getInternal("model-orders"));
        assertSame(nodeTypes, adapter.pageWorkbenchWorkflowNodeTypes());
        verify(capabilityClient).getProjectById(7L);
        verify(capabilityClient).listProjectTools(7L);
        verify(modelClient).getInternal("model-orders");
        verify(runtimeClient).pageWorkbenchWorkflowNodeTypes();
    }
}
