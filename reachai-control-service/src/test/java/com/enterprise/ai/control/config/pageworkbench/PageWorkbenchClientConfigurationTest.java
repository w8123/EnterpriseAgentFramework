package com.enterprise.ai.control.config.pageworkbench;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchRuntimePort;
import feign.Client;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PageWorkbenchClientConfigurationTest {
    @Test
    void createsFeignBindingAndDecodesPublishedWorkflowThroughApplicationPort() {
        var sent = new AtomicReference<Request>();
        Client transport = (request, options) -> {
            sent.set(request);
            return Response.builder().request(request).status(200).reason("OK")
                    .headers(Map.of("Content-Type", List.of("application/json")))
                    .body("[{\"workflowId\":\"wf-orders\",\"workflowVersionId\":9,\"workflowVersion\":\"v1\"}]",
                            StandardCharsets.UTF_8).build();
        };
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class))
                .withUserConfiguration(PageWorkbenchClientConfiguration.class, PageWorkbenchClientAdapter.class)
                .withPropertyValues("services.runtime-service.url=http://runtime.test")
                .withBean(Client.class, () -> transport)
                .withBean(CapabilityProjectOnboardingClient.class, () -> mock(CapabilityProjectOnboardingClient.class))
                .withBean(ControlModelCatalogClient.class, () -> mock(ControlModelCatalogClient.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var port = context.getBean(PageWorkbenchRuntimePort.class);
                    var response = port.pageWorkbenchPublished("orders", "orders-list");
                    assertEquals(200, response.getStatusCode().value());
                    assertNotNull(response.getBody());
                    assertEquals("wf-orders", response.getBody().get(0).workflowId());
                    assertEquals(9L, response.getBody().get(0).workflowVersionId());
                    assertEquals("http://runtime.test/internal/runtime/page-workbench/projects/orders/published?pageKey=orders-list",
                            sent.get().url());
                });
    }
}
