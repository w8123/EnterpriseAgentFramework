package com.enterprise.ai.model.internal;

import com.enterprise.ai.model.instance.ModelInstanceResponse;
import com.enterprise.ai.model.instance.ModelInstanceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelInstanceInternalControllerTest {

    @Test
    void listsOnlyActiveLlmWhenFiltered() {
        ModelInstanceService service = mock(ModelInstanceService.class);
        when(service.list(isNull(), eq("LLM"), isNull(), isNull(), anyBoolean())).thenReturn(List.of(
                ModelInstanceResponse.builder().id("m1").name("Active").modelType("LLM").status("ACTIVE").provider("p").modelName("g").lastTestStatus("SUCCESS").lastTestAt(java.time.LocalDateTime.of(2026, 8, 12, 20, 0)).lastTestLatencyMs(120L).build(),
                ModelInstanceResponse.builder().id("m2").name("Disabled").modelType("LLM").status("DISABLED").provider("p").modelName("g").build(),
                ModelInstanceResponse.builder().id("m3").name("Embed").modelType("EMBEDDING").status("ACTIVE").provider("p").modelName("e").build()
        ));
        ModelInstanceInternalController controller = new ModelInstanceInternalController(service);

        ResponseEntity<List<Map<String, Object>>> response = controller.list("LLM", "ACTIVE");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("m1", response.getBody().get(0).get("id"));
        assertEquals("ACTIVE", response.getBody().get(0).get("status"));
        assertEquals("LLM", response.getBody().get(0).get("modelType"));
        assertEquals("SUCCESS", response.getBody().get(0).get("lastTestStatus"));
        assertEquals(120L, response.getBody().get(0).get("lastTestLatencyMs"));
    }

    @Test
    void returnsEmptyListWhenNoActiveLlm() {
        ModelInstanceService service = mock(ModelInstanceService.class);
        when(service.list(isNull(), eq("LLM"), isNull(), isNull(), anyBoolean())).thenReturn(List.of());
        ModelInstanceInternalController controller = new ModelInstanceInternalController(service);

        ResponseEntity<List<Map<String, Object>>> response = controller.list("LLM", "ACTIVE");

        assertEquals(0, response.getBody().size());
    }

    @Test
    void getsOneSafeCatalogItemWithTestState() {
        ModelInstanceService service = mock(ModelInstanceService.class);
        when(service.get("m1")).thenReturn(ModelInstanceResponse.builder()
                .id("m1")
                .name("Active")
                .modelType("LLM")
                .status("ACTIVE")
                .provider("p")
                .modelName("g")
                .connection(Map.of("apiKey", "must-not-leak"))
                .lastTestStatus("FAILED")
                .lastTestError("must-not-leak")
                .build());
        ModelInstanceInternalController controller =
                new ModelInstanceInternalController(service);

        Map<String, Object> body = controller.get("m1").getBody();

        assertEquals("m1", body.get("id"));
        assertEquals("FAILED", body.get("lastTestStatus"));
        org.junit.jupiter.api.Assertions.assertFalse(body.containsKey("connection"));
        org.junit.jupiter.api.Assertions.assertFalse(body.containsKey("lastTestError"));
    }
}
