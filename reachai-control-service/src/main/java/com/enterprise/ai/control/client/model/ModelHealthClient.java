package com.enterprise.ai.control.client.model;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(
        name = "reachai-model-health",
        url = "${services.model-service.url:http://localhost:18601}",
        configuration = com.enterprise.ai.control.internal.InternalHealthFeignConfig.class
)
public interface ModelHealthClient {

    @GetMapping("/actuator/health")
    Map<String, Object> health();
}
