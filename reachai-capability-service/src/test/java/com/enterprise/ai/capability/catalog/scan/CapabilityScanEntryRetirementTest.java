package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Legacy requests may inspect their scoped source row, but must never dispatch or mutate it. */
class CapabilityScanEntryRetirementTest {
    @Test void manualProjectionAndDirectTestRoutesAreGoneWithOwnerDirections() throws Exception {
        var service = mock(CapabilityScanProjectCatalogService.class);
        var project = new ScanProjectEntity(); project.setId(7L); project.setProjectCode("orders");
        var row = new ScanProjectToolEntity(); row.setId(11L); row.setProjectId(7L);
        when(service.get(7L)).thenReturn(project);
        when(service.getTool(7L, 11L)).thenReturn(row);
        var mvc = MockMvcBuilders.standaloneSetup(new CapabilityScanProjectCatalogController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
        for (String action : List.of("promote-to-tool", "push-to-global-tool", "unpromote-from-global", "test")) {
            mvc.perform(post("/api/scan-projects/7/scan-tools/11/" + action))
                    .andExpect(status().isGone()).andExpect(jsonPath("code").value("SCAN_EXECUTION_ENTRY_RETIRED"))
                    .andExpect(jsonPath("projectCode").value("orders"))
                    .andExpect(jsonPath("apiDirectory").value("/apis"))
                    .andExpect(jsonPath("businessMethodDirectory").value("/business-methods"));
        }
        mvc.perform(post("/api/scan-projects/7/scan-tools/promote-by-module"))
                .andExpect(status().isGone());
        for (String suffix : List.of("", "/toggle")) {
            mvc.perform(put("/api/scan-projects/7/scan-tools/11" + suffix).contentType("application/json").content("{}"))
                    .andExpect(status().isGone());
        }
        // Only the owner lookup methods above are authorized dependencies of retirement.
        assertEquals(13, mockingDetails(service).getInvocations().size());
        verify(service, times(7)).get(7L);
        verify(service, times(6)).getTool(7L, 11L);
        verifyNoMoreInteractions(service);
    }

    @Test void missingAndCrossProjectRowsDoNotRevealReplacementContext() throws Exception {
        var service = mock(CapabilityScanProjectCatalogService.class);
        var project = new ScanProjectEntity(); project.setId(7L); project.setProjectCode("orders");
        when(service.get(7L)).thenReturn(project);
        when(service.getTool(7L, 99L)).thenThrow(new IllegalArgumentException("Scan project tool does not exist"));
        when(service.get(99L)).thenThrow(new IllegalArgumentException("Scan project does not exist"));
        var mvc = MockMvcBuilders.standaloneSetup(new CapabilityScanProjectCatalogController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
        mvc.perform(post("/api/scan-projects/7/scan-tools/99/promote-to-tool"))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        mvc.perform(post("/api/scan-projects/99/scan-tools/promote-by-module"))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
    }
}
