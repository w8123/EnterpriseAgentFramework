package com.enterprise.ai.capability.catalog.scan;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Source observations remain readable; independent maintenance and execution have no route. */
class CapabilityScanEntryRetirementTest {
    @Test void manualProjectionAndDirectTestRoutesHaveNoHandlerOrOwnerLookup() throws Exception {
        var service = mock(CapabilityScanProjectCatalogService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CapabilityScanProjectCatalogController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
        for (String action : List.of("promote-to-tool", "push-to-global-tool", "unpromote-from-global", "test")) {
            mvc.perform(post("/api/scan-projects/7/scan-tools/11/" + action))
                    .andExpect(status().isNotFound()).andExpect(content().string(""));
        }
        mvc.perform(post("/api/scan-projects/7/scan-tools/promote-by-module"))
                .andExpect(status().isMethodNotAllowed()).andExpect(content().string(""));
        for (String suffix : List.of("", "/toggle")) {
            var response = mvc.perform(put("/api/scan-projects/7/scan-tools/11" + suffix)
                    .contentType("application/json").content("{}"))
                    .andExpect(content().string("")).andReturn().getResponse();
            assertEquals(suffix.isEmpty() ? 405 : 404, response.getStatus());
        }
        verifyNoInteractions(service);
    }

    @Test void missingAndCrossProjectSourceReadsRemainScoped() throws Exception {
        var service = mock(CapabilityScanProjectCatalogService.class);
        when(service.getTool(7L, 99L)).thenThrow(new IllegalArgumentException("Scan project tool does not exist"));
        when(service.getTool(99L, 11L)).thenThrow(new IllegalArgumentException("Scan project tool does not exist"));
        var mvc = MockMvcBuilders.standaloneSetup(new CapabilityScanProjectCatalogController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
        mvc.perform(get("/api/scan-projects/7/scan-tools/99"))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        mvc.perform(get("/api/scan-projects/99/scan-tools/11"))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    @Test void projectOperationIsExplicitAndDeletionReturnsTypedOwnerEvidence() throws Exception {
        var service = mock(CapabilityScanProjectCatalogService.class);
        var blockers = new com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers(true, List.of(), List.of(),
                List.of(new com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers.AssetRef(
                        "BUSINESS_METHOD", 21L, "orders:read", "查询订单")));
        when(service.operationBlockers(7L, com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers.Operation.DELETE))
                .thenReturn(blockers);
        when(service.operationBlockers(7L, com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers.Operation.RESCAN))
                .thenReturn(com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers.empty());
        var mvc = MockMvcBuilders.standaloneSetup(new CapabilityScanProjectCatalogController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
        mvc.perform(get("/api/scan-projects/7/operation-blockers"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/scan-projects/7/operation-blockers").param("operation", "CREATE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/scan-projects/7/operation-blockers").param("operation", "DELETE"))
                .andExpect(status().isOk()).andExpect(jsonPath("blocked").value(true))
                .andExpect(jsonPath("assets[0].assetType").value("BUSINESS_METHOD"))
                .andExpect(jsonPath("assets[0].assetId").value(21))
                .andExpect(jsonPath("assets[0].qualifiedName").value("orders:read"))
                .andExpect(jsonPath("tools").isArray()).andExpect(jsonPath("agents").isArray());
        mvc.perform(get("/api/scan-projects/7/operation-blockers").param("operation", "RESCAN"))
                .andExpect(status().isOk()).andExpect(jsonPath("blocked").value(false));
        assertEquals(2, mockingDetails(service).getInvocations().size(), "invalid operations must not reach the service");
    }
}
