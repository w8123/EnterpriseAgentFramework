package com.enterprise.ai.runtime.client.model;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeModelServiceClientContractTest {

    @Test
    void pointsRuntimeChatToModelGateway() throws Exception {
        FeignClient feignClient = RuntimeModelServiceClient.class.getAnnotation(FeignClient.class);
        Method chat = RuntimeModelServiceClient.class
                .getDeclaredMethod("chat", RuntimeModelServiceClient.ModelChatRequest.class);

        assertEquals("reachai-model-service", feignClient.name());
        assertEquals("${services.model-service.url:http://localhost:18601}", feignClient.url());
        assertEquals("/model", feignClient.path());
        assertEquals("/chat", chat.getAnnotation(PostMapping.class).value()[0]);
    }
}
