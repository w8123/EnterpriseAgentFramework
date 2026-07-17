package com.enterprise.ai.control.client.runtime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(
        name = "reachai-runtime-health",
        url = "${services.runtime-service.url:http://localhost:18604}",
        configuration = com.enterprise.ai.control.internal.InternalHealthFeignConfig.class
)
public interface RuntimeHealthClient {

    @GetMapping("/internal/runtime/health")
    Map<String, Object> health();
}
