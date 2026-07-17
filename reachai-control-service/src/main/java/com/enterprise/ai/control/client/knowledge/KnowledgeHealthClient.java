package com.enterprise.ai.control.client.knowledge;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(
        name = "reachai-knowledge-health",
        url = "${services.knowledge-service.url:http://localhost:18602}",
        configuration = com.enterprise.ai.control.internal.InternalHealthFeignConfig.class
)
public interface KnowledgeHealthClient {

    /** Knowledge service uses servlet context-path `/ai`. */
    @GetMapping("/ai/actuator/health")
    Map<String, Object> health();
}
