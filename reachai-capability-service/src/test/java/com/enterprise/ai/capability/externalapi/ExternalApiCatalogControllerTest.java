package com.enterprise.ai.capability.externalapi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalApiCatalogControllerTest {

    @Test
    void exposesApiMarketAsASeparateCapabilityCatalogSurface() throws Exception {
        RequestMapping mapping = ExternalApiCatalogController.class.getAnnotation(RequestMapping.class);
        Method entries = ExternalApiCatalogController.class.getDeclaredMethod(
                "listEntries", int.class, int.class, String.class, String.class, String.class,
                String.class, String.class, String.class, Boolean.class);
        Method detail = ExternalApiCatalogController.class.getDeclaredMethod("getEntry", String.class);
        Method createIntegration = ExternalApiCatalogController.class.getDeclaredMethod(
                "createIntegration", String.class, Map.class);
        Method updateStatus = ExternalApiCatalogController.class.getDeclaredMethod(
                "updateIntegrationStatus", Long.class, Map.class);

        assertArrayEquals(new String[] {"/api/api-market", "/internal/capability/api-market"}, mapping.value());
        assertArrayEquals(new String[] {"/entries"}, entries.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/entries/{entryKey}"}, detail.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/entries/{entryKey}/integrations"},
                createIntegration.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/integrations/{integrationId}/status"},
                updateStatus.getAnnotation(PutMapping.class).value());
    }

    @Test
    void delegatesDiscoveryQueryToCatalogService() {
        ExternalApiCatalogService service = mock(ExternalApiCatalogService.class);
        ExternalApiCatalogController controller = new ExternalApiCatalogController(service);
        ExternalApiCatalogViews.PageView page = new ExternalApiCatalogViews.PageView(
                List.of(), 0, 1, 24, 0);
        when(service.listEntries(
                1, 24, "天气", "WEATHER", "NONE", "VERIFIED", "VALID", "apis-guru", true))
                .thenReturn(page);

        var response = controller.listEntries(
                1, 24, "天气", "WEATHER", "NONE", "VERIFIED", "VALID", "apis-guru", true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(page, response.getBody());
    }
}
