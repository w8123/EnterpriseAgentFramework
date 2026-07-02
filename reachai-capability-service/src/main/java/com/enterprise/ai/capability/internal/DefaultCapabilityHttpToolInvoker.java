package com.enterprise.ai.capability.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DefaultCapabilityHttpToolInvoker implements CapabilityHttpToolInvoker {

    private final RestTemplateBuilder restTemplateBuilder;

    @Override
    public Map<String, Object> invoke(CapabilityHttpToolInvocation invocation) {
        RestTemplate restTemplate = restTemplateBuilder.build();
        HttpMethod method = HttpMethod.valueOf(invocation.method());
        HttpEntity<?> entity = method == HttpMethod.GET
                ? HttpEntity.EMPTY
                : new HttpEntity<>(invocation.body());
        ResponseEntity<Object> response = restTemplate.exchange(invocation.url(), method, entity, Object.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statusCode", response.getStatusCode().value());
        body.put("body", response.getBody());
        return body;
    }
}
