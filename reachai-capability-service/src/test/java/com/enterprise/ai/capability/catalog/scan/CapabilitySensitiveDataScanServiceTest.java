package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilitySensitiveDataScanServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CapabilityModelClient modelClient = mock(CapabilityModelClient.class);
    private final ScanProjectToolMapper scanProjectToolMapper = mock(ScanProjectToolMapper.class);
    private final CapabilitySensitiveDataScanService service =
            new CapabilitySensitiveDataScanService(objectMapper, modelClient, scanProjectToolMapper);

    @Test
    void scansToolThroughModelServiceAndPersistsSensitiveDataJson() throws Exception {
        ScanProjectToolEntity tool = tool();
        when(modelClient.chat(any())).thenReturn(com.enterprise.ai.common.dto.ApiResult.ok(
                new CapabilityModelClient.ChatResponse(
                        "{\"types\":[\"PHONE\",\"EMAIL\",\"UNKNOWN\"],\"summary\":\"contains contact fields\"}",
                        "qwen-plus",
                        null,
                        new CapabilityModelClient.Usage(10, 7, 17))));

        int tokens = service.scanAndPersist(tool, "model-main");

        assertEquals(17, tokens);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ScanProjectToolEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(scanProjectToolMapper).update(isNull(), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> updateParams = (Map<String, Object>) captor.getValue()
                .getClass()
                .getMethod("getParamNameValuePairs")
                .invoke(captor.getValue());
        String json = updateParams.values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> value.contains("contains contact fields"))
                .findFirst()
                .orElseThrow();
        JsonNode stored = objectMapper.readTree(json);
        assertEquals(List.of("PHONE", "EMAIL"), objectMapper.convertValue(
                stored.get("types"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("contains contact fields", stored.get("summary").asText());
        assertEquals("qwen-plus", stored.get("modelName").asText());
    }

    @Test
    void persistsFailurePlaceholder() {
        service.persistFailure(11L, "boom", "model-main");

        verify(scanProjectToolMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void stripsMarkdownJsonFenceBeforeParsing() {
        assertEquals("{\"types\":[]}", CapabilitySensitiveDataScanService.stripJsonFences("""
                ```json
                {"types":[]}
                ```
                """));
    }

    private ScanProjectToolEntity tool() {
        ScanProjectToolEntity tool = new ScanProjectToolEntity();
        tool.setId(11L);
        tool.setName("orders_create");
        tool.setDescription("Create order");
        tool.setHttpMethod("POST");
        tool.setBaseUrl("https://api.example.com");
        tool.setContextPath("/api");
        tool.setEndpointPath("/orders");
        tool.setRequestBodyType("OrderCreateRequest");
        tool.setResponseType("OrderDTO");
        tool.setSourceLocation("OrderController#create");
        tool.setParametersJson("[{\"name\":\"phone\",\"type\":\"string\",\"location\":\"body\"}]");
        tool.setCapabilityMetadataJson(objectMapper.valueToTree(Map.of("module", "Order")).toString());
        return tool;
    }
}
