package com.enterprise.ai.runtime.client.capability;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeCapabilityCatalogClientContractTest {

    @Test
    void runtimeUsesCapabilityServiceInternalToolLookupRoute() throws Exception {
        FeignClient feignClient = RuntimeCapabilityCatalogClient.class.getAnnotation(FeignClient.class);
        assertEquals("reachai-capability-service", feignClient.name());
        assertEquals("${services.capability-service.url:http://localhost:18605}", feignClient.url());

        Method getToolDefinition = RuntimeCapabilityCatalogClient.class.getMethod("getToolDefinition", String.class);
        GetMapping mapping = getToolDefinition.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/tools/{qualifiedName}"}, mapping.value());
        assertEquals(Map.class, getToolDefinition.getReturnType());

        Method executeTool = RuntimeCapabilityCatalogClient.class.getMethod("executeTool", String.class, Map.class);
        PostMapping executeMapping = executeTool.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/tools/{qualifiedName}/execute"}, executeMapping.value());
        assertEquals(Map.class, executeTool.getReturnType());

        Method getCompositionDefinition = RuntimeCapabilityCatalogClient.class
                .getMethod("getCompositionDefinition", String.class);
        GetMapping compositionMapping = getCompositionDefinition.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/compositions/{qualifiedName}"},
                compositionMapping.value());
        assertEquals(Map.class, getCompositionDefinition.getReturnType());

        Method getProject = RuntimeCapabilityCatalogClient.class.getMethod("getProject", String.class);
        GetMapping projectMapping = getProject.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/projects/{projectCode}"}, projectMapping.value());
        assertEquals(Map.class, getProject.getReturnType());

        Method getProjectById = RuntimeCapabilityCatalogClient.class.getMethod("getProjectById", Long.class);
        GetMapping projectByIdMapping = getProjectById.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/projects/by-id/{projectId}"},
                projectByIdMapping.value());
        assertEquals(Map.class, getProjectById.getReturnType());

        Method listRuntimeInstances = RuntimeCapabilityCatalogClient.class.getMethod("listRuntimeInstances");
        GetMapping runtimeInstancesMapping = listRuntimeInstances.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/runtime-instances"},
                runtimeInstancesMapping.value());
        assertEquals(List.class, listRuntimeInstances.getReturnType());
    }
}
