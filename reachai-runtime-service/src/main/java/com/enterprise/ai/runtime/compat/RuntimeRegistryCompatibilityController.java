package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.registry.RuntimeEmbeddedDispatchRequest;
import com.enterprise.ai.runtime.registry.RuntimeEmbeddedDispatchResult;
import com.enterprise.ai.runtime.registry.RuntimeEmbeddedDispatchService;
import com.enterprise.ai.runtime.registry.RuntimeRegistryEntry;
import com.enterprise.ai.runtime.registry.RuntimeRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimeRegistryCompatibilityController {

    private final RuntimeRegistryService registryService;
    private final RuntimeEmbeddedDispatchService embeddedDispatchService;

    @GetMapping("/api/runtimes")
    public ResponseEntity<List<RuntimeRegistryEntry>> listRuntimes() {
        return ResponseEntity.ok(registryService.listRuntimes());
    }

    @PostMapping("/api/runtimes/embedded/dispatch")
    public ResponseEntity<RuntimeEmbeddedDispatchResult> dispatchEmbedded(
            @RequestBody RuntimeEmbeddedDispatchRequest request) {
        return ResponseEntity.ok(embeddedDispatchService.dispatch(request));
    }
}
