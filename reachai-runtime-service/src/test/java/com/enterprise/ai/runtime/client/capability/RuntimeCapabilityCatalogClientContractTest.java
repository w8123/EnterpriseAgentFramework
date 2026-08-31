package com.enterprise.ai.runtime.client.capability;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.enterprise.ai.common.capability.CapabilityInvocationResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeCapabilityCatalogClientContractTest {

    @Test
    void runtimeUsesCapabilityServiceInternalToolLookupRoute() throws Exception {
        assertNull(RuntimeCapabilityCatalogClient.class.getAnnotation(FeignClient.class),
                "the public abstraction must not expose an unsigned Feign execution method");

        FeignClient feignClient = RuntimeCapabilityCatalogFeignClient.class.getAnnotation(FeignClient.class);
        assertEquals("reachai-capability-service", feignClient.name());
        assertEquals("runtimeCapabilityCatalogFeignClient", feignClient.contextId());
        assertEquals("${services.capability-service.url:http://localhost:18605}", feignClient.url());

        Method getToolDefinition = RuntimeCapabilityCatalogFeignClient.class
                .getMethod("getToolDefinition", String.class);
        GetMapping mapping = getToolDefinition.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/tools/{qualifiedName}"}, mapping.value());
        assertEquals(Map.class, getToolDefinition.getReturnType());

        Method invokeTool = RuntimeCapabilityCatalogFeignClient.class
                .getMethod("invokeTool", String.class, Map.class, byte[].class);
        PostMapping executeMapping = invokeTool.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/tools/{qualifiedName}/execute"}, executeMapping.value());
        assertArrayEquals(new String[] {"application/json"}, executeMapping.consumes());
        assertEquals(RequestHeader.class,
                invokeTool.getParameterAnnotations()[1][0].annotationType());
        assertEquals(RequestBody.class,
                invokeTool.getParameterAnnotations()[2][0].annotationType());
        assertEquals(CapabilityInvocationResponse.class, invokeTool.getReturnType());

        Method invokeCapability = RuntimeCapabilityCatalogFeignClient.class
                .getMethod("invokeCapability", Map.class, byte[].class);
        PostMapping invocationMapping = invokeCapability.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/invocations"}, invocationMapping.value());
        assertArrayEquals(new String[] {"application/json"}, invocationMapping.consumes());
        assertEquals(CapabilityInvocationResponse.class, invokeCapability.getReturnType());

        Method getCompositionDefinition = RuntimeCapabilityCatalogFeignClient.class
                .getMethod("getCompositionDefinition", String.class);
        GetMapping compositionMapping = getCompositionDefinition.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/compositions/{qualifiedName}"},
                compositionMapping.value());
        assertEquals(Map.class, getCompositionDefinition.getReturnType());

        Method getProject = RuntimeCapabilityCatalogFeignClient.class.getMethod("getProject", String.class);
        GetMapping projectMapping = getProject.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/projects/{projectCode}"}, projectMapping.value());
        assertEquals(Map.class, getProject.getReturnType());

        Method getProjectById = RuntimeCapabilityCatalogFeignClient.class.getMethod("getProjectById", Long.class);
        GetMapping projectByIdMapping = getProjectById.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/projects/by-id/{projectId}"},
                projectByIdMapping.value());
        assertEquals(Map.class, getProjectById.getReturnType());

        Method listProjectTools = RuntimeCapabilityCatalogFeignClient.class.getMethod("listProjectTools", Long.class);
        GetMapping toolsMapping = listProjectTools.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/projects/by-id/{projectId}/tools"},
                toolsMapping.value());
        assertEquals(List.class, listProjectTools.getReturnType());

        Method readinessFacts = RuntimeCapabilityCatalogFeignClient.class
                .getMethod("projectReadinessFacts", Long.class);
        GetMapping readinessMapping = readinessFacts.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/projects/by-id/{projectId}/readiness-facts"},
                readinessMapping.value());
        assertEquals(Map.class, readinessFacts.getReturnType());
    }
}
