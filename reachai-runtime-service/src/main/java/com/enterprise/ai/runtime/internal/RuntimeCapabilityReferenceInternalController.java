package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.internal.RuntimeCapabilityReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimeCapabilityReferenceInternalController {
    private final RuntimeCapabilityReferenceService references;

    @PostMapping("/internal/runtime/capability-references")
    public RuntimeCapabilityReferenceService.Evidence inspect(@RequestBody RuntimeCapabilityReferenceService.Query query) {
        return references.inspect(query);
    }
}
