package com.enterprise.ai.agent.runtime.host.controller;

import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchRequest;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchResult;
import com.enterprise.ai.agent.runtime.host.EmbeddedRuntimeDispatchService;
import com.enterprise.ai.agent.runtime.RuntimeRegistryEntry;
import com.enterprise.ai.agent.runtime.host.RuntimeRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runtimes")
@RequiredArgsConstructor
public class RuntimeRegistryController {

    private final RuntimeRegistryService runtimeRegistryService;
    private final EmbeddedRuntimeDispatchService embeddedRuntimeDispatchService;

    @GetMapping
    public List<RuntimeRegistryEntry> listRuntimes() {
        return runtimeRegistryService.list();
    }

    @PostMapping("/embedded/dispatch")
    public EmbeddedRuntimeDispatchResult dispatchEmbedded(@RequestBody EmbeddedRuntimeDispatchRequest request) {
        return embeddedRuntimeDispatchService.dispatch(request);
    }
}
