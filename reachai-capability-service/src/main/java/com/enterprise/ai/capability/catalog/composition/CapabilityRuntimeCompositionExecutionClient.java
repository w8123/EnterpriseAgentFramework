package com.enterprise.ai.capability.catalog.composition;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "reachai-runtime-composition-execution", url = "${services.runtime-service.url:http://localhost:18604}")
public interface CapabilityRuntimeCompositionExecutionClient {

    @PostMapping("/api/runtime/compositions/{qualifiedName}/execute")
    Map<String, Object> executeComposition(@PathVariable("qualifiedName") String qualifiedName,
                                           @RequestBody Map<String, Object> body);
}
