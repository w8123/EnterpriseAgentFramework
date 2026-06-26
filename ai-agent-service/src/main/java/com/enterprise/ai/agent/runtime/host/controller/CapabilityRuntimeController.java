package com.enterprise.ai.agent.runtime.host.controller;

import com.enterprise.ai.agent.runtime.CapabilityRuntimeRequest;
import com.enterprise.ai.agent.runtime.host.CompositionRuntimeExecutor;
import com.enterprise.ai.agent.runtime.host.ToolRuntimeExecutor;
import com.enterprise.ai.agent.runtime.ToolRuntimeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/runtime")
@RequiredArgsConstructor
public class CapabilityRuntimeController {

    private final ToolRuntimeExecutor toolRuntimeExecutor;
    private final CompositionRuntimeExecutor compositionRuntimeExecutor;

    @PostMapping("/tools/{qualifiedName}/execute")
    public Object executeTool(@PathVariable String qualifiedName,
                              @RequestBody(required = false) RuntimeExecuteRequest request) {
        return toolRuntimeExecutor.execute(ToolRuntimeRequest.builder()
                .qualifiedName(qualifiedName)
                .args(request == null || request.params() == null ? Map.of() : request.params())
                .context(request == null || request.context() == null ? Map.of() : request.context())
                .build());
    }

    @PostMapping("/compositions/{qualifiedName}/execute")
    public Object executeComposition(@PathVariable String qualifiedName,
                                     @RequestBody(required = false) RuntimeExecuteRequest request) {
        return compositionRuntimeExecutor.execute(CapabilityRuntimeRequest.builder()
                .qualifiedName(qualifiedName)
                .params(request == null || request.params() == null ? Map.of() : request.params())
                .context(request == null || request.context() == null ? Map.of() : request.context())
                .build());
    }

    @PostMapping("/interactions/{sessionId}/resume")
    public Object resumeInteraction(@PathVariable String sessionId,
                                    @RequestBody(required = false) RuntimeExecuteRequest request) {
        return compositionRuntimeExecutor.resumeInteraction(
                sessionId,
                request == null || request.params() == null ? Map.of() : request.params(),
                request == null || request.context() == null ? Map.of() : request.context());
    }

    public record RuntimeExecuteRequest(Map<String, Object> params, Map<String, Object> context) {
    }
}
