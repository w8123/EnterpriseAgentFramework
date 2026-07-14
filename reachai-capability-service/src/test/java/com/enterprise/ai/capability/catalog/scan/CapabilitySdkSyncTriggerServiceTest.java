package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.reach.sdk.auth.ReachAiSignatureHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilitySdkSyncTriggerServiceTest {

    @Test
    void triggerScanPostsSignedRequestToLatestOnlineSdkInstance() {
        ScanProjectMapper scanProjectMapper = mock(ScanProjectMapper.class);
        ProjectInstanceMapper instanceMapper = mock(ProjectInstanceMapper.class);
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        RestTemplateBuilder restTemplateBuilder = mock(RestTemplateBuilder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CapabilitySdkSyncTriggerService service = new CapabilitySdkSyncTriggerService(
                scanProjectMapper,
                instanceMapper,
                registrySecurityService,
                restTemplateBuilder);

        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setName("Orders");
        project.setProjectCode("orders");
        project.setBaseUrl("https://fallback.example.com");
        project.setContextPath("/orders-api");
        ProjectInstanceEntity instance = new ProjectInstanceEntity();
        instance.setId(11L);
        instance.setInstanceId("dev-1");
        instance.setProjectId(7L);
        instance.setProjectCode("orders");
        instance.setBaseUrl("https://orders.example.com");
        instance.setStatus("ONLINE");
        instance.setLastHeartbeatAt(LocalDateTime.now());
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-key");
        credential.setAppSecret("orders-secret");

        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(instanceMapper.selectOne(any())).thenReturn(instance);
        when(registrySecurityService.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(
                eq("https://orders.example.com/orders-api/reachai/registry/capabilities/sync"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("capabilityCount", 3, "registryResponse", "{}")));

        CapabilitySdkSyncTriggerService.SdkSyncTriggerResponse response = service.triggerScan(7L);

        assertEquals(7L, response.projectId());
        assertEquals("orders", response.projectCode());
        assertEquals("dev-1", response.instanceId());
        assertEquals("https://orders.example.com/orders-api/reachai/registry/capabilities/sync", response.targetUrl());
        assertEquals(3, response.capabilityCount());
        assertEquals("{}", response.businessResponse().get("registryResponse"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://orders.example.com/orders-api/reachai/registry/capabilities/sync"),
                eq(HttpMethod.POST),
                requestCaptor.capture(),
                eq(Map.class));
        HttpEntity<Map<String, Object>> request = requestCaptor.getValue();
        assertEquals("orders-key", request.getHeaders().getFirst(ReachAiSignatureHeaders.HEADER_APP_KEY));
        assertNotNull(request.getHeaders().getFirst(ReachAiSignatureHeaders.HEADER_TIMESTAMP));
        assertNotNull(request.getHeaders().getFirst(ReachAiSignatureHeaders.HEADER_NONCE));
        assertNotNull(request.getHeaders().getFirst(ReachAiSignatureHeaders.HEADER_SIGNATURE));
        assertEquals("API_MANUAL_SCAN", request.getBody().get("source"));
    }

    @Test
    void explainsBusinessSecurityBoundaryWhenSyncCallbackIsForbidden() {
        CapabilitySdkSyncTriggerService service = serviceFailingWith(
                new HttpClientErrorException(HttpStatus.FORBIDDEN));

        CapabilitySdkSyncTriggerService.SdkSyncRequestException error = assertThrows(
                CapabilitySdkSyncTriggerService.SdkSyncRequestException.class,
                () -> service.triggerScan(7L));

        assertEquals("SDK_SYNC_AUTH_REJECTED", error.code());
        assertEquals("https://orders.example.com/orders-api/reachai/registry/capabilities/sync", error.targetUrl());
        assertTrue(error.getMessage().contains("业务登录/JWT"));
        assertTrue(error.getMessage().contains("Starter 签名校验"));
    }

    @Test
    void explainsReachableBaseUrlWhenSyncCallbackCannotConnect() {
        CapabilitySdkSyncTriggerService service = serviceFailingWith(
                new ResourceAccessException("Connection refused"));

        CapabilitySdkSyncTriggerService.SdkSyncRequestException error = assertThrows(
                CapabilitySdkSyncTriggerService.SdkSyncRequestException.class,
                () -> service.triggerScan(7L));

        assertEquals("SDK_SYNC_TARGET_UNREACHABLE", error.code());
        assertTrue(error.getMessage().contains("ReachAI 服务端可达地址"));
        assertTrue(error.getMessage().contains("不要使用 localhost"));
    }

    private CapabilitySdkSyncTriggerService serviceFailingWith(RestClientException failure) {
        ScanProjectMapper scanProjectMapper = mock(ScanProjectMapper.class);
        ProjectInstanceMapper instanceMapper = mock(ProjectInstanceMapper.class);
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        RestTemplateBuilder restTemplateBuilder = mock(RestTemplateBuilder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setName("Orders");
        project.setProjectCode("orders");
        project.setBaseUrl("https://fallback.example.com");
        project.setContextPath("/orders-api");
        ProjectInstanceEntity instance = new ProjectInstanceEntity();
        instance.setId(11L);
        instance.setInstanceId("dev-1");
        instance.setProjectId(7L);
        instance.setProjectCode("orders");
        instance.setBaseUrl("https://orders.example.com");
        instance.setStatus("ONLINE");
        instance.setLastHeartbeatAt(LocalDateTime.now());
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-key");
        credential.setAppSecret("orders-secret");

        when(scanProjectMapper.selectById(7L)).thenReturn(project);
        when(instanceMapper.selectOne(any())).thenReturn(instance);
        when(registrySecurityService.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(
                eq("https://orders.example.com/orders-api/reachai/registry/capabilities/sync"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenThrow(failure);

        return new CapabilitySdkSyncTriggerService(
                scanProjectMapper,
                instanceMapper,
                registrySecurityService,
                restTemplateBuilder);
    }
}
