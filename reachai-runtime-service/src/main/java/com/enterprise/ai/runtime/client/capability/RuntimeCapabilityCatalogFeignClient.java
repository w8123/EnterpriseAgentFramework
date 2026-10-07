package com.enterprise.ai.runtime.client.capability;

import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

/** Raw transport. Tool calls must pass through the guarded Runtime execution gateway. */
@FeignClient(
        name = "reachai-capability-service",
        contextId = "runtimeCapabilityCatalogFeignClient",
        url = "${services.capability-service.url:http://localhost:18605}")
public interface RuntimeCapabilityCatalogFeignClient {

    @GetMapping("/internal/capability/tools/{qualifiedName}")
    Map<String, Object> getToolDefinition(@PathVariable("qualifiedName") String qualifiedName);

    @PostMapping(value = RuntimeCapabilityInternalAuthSigner.TOOL_EXECUTE_PATH_TEMPLATE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    CapabilityInvocationResponse invokeTool(@PathVariable("qualifiedName") String qualifiedName,
                                            @RequestHeader Map<String, String> internalAuthHeaders,
                                            @RequestBody byte[] exactBody);

    @PostMapping(value = RuntimeCapabilityInternalAuthSigner.CAPABILITY_INVOCATION_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    CapabilityInvocationResponse invokeCapability(
            @RequestHeader Map<String, String> internalAuthHeaders,
            @RequestBody byte[] exactBody);

    @GetMapping("/internal/capability/projects/{projectCode}")
    Map<String, Object> getProject(@PathVariable("projectCode") String projectCode);

    @GetMapping("/internal/capability/projects/by-id/{projectId}")
    Map<String, Object> getProjectById(@PathVariable("projectId") Long projectId);

    @GetMapping("/internal/capability/projects/by-id/{projectId}/tools")
    List<Map<String, Object>> listProjectTools(@PathVariable("projectId") Long projectId);

    @GetMapping("/internal/capability/projects/by-id/{projectId}/readiness-facts")
    Map<String, Object> projectReadinessFacts(@PathVariable("projectId") Long projectId);

    @PostMapping(value = "/internal/capability/http-apis/{apiId}/execution-context",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    HttpApiConsoleContracts.ExecutionContext httpApiExecutionContext(
            @PathVariable("apiId") Long apiId,
            @RequestHeader Map<String, String> internalAuthHeaders,
            @RequestBody byte[] exactBody);

    @PostMapping(value = "/internal/capability/business-methods/{qualifiedName}/execution-context",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ConsoleCapabilityInvocationContracts.InvocationContext businessMethodExecutionContext(
            @PathVariable("qualifiedName") String qualifiedName,
            @RequestHeader Map<String, String> internalAuthHeaders,
            @RequestBody byte[] exactBody);
}
