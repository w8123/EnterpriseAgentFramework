package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityProjectBusinessMethodStatesInternalControllerTest {

    @Test
    void reportsAcceptedPendingAndMissingNamesInsideTheProject() {
        CapabilityScanProjectCatalogService projects = mock(CapabilityScanProjectCatalogService.class);
        BusinessMethodAssetMapper assets = mock(BusinessMethodAssetMapper.class);
        CapabilityDiffItemMapper changes = mock(CapabilityDiffItemMapper.class);
        BusinessMethodAssetEntity accepted = new BusinessMethodAssetEntity();
        accepted.setProjectId(7L);
        accepted.setQualifiedName("yudao-erp:erpStockPage");
        accepted.setAcceptedRevisionId(11L);
        accepted.setEnabled(true);
        accepted.setSideEffect("READ");
        CapabilityDiffItemEntity pending = new CapabilityDiffItemEntity();
        pending.setId(5L);
        pending.setName("erpReceivablePage");
        pending.setReviewStatus("PENDING");
        when(assets.selectList(any())).thenReturn(List.of(accepted));
        when(changes.selectList(any())).thenReturn(List.of(pending));

        ResponseEntity<?> response = new CapabilityProjectBusinessMethodStatesInternalController(projects, assets, changes)
                .states(7L, List.of("erpStockPage", "erpReceivablePage", "erpMissing"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) response.getBody()).get("items");
        assertEquals("ACCEPTED", items.get(0).get("state"));
        assertEquals("PENDING_REVIEW", items.get(1).get("state"));
        assertEquals(5L, items.get(1).get("pendingChangeId"));
        assertEquals("NOT_SYNCED", items.get(2).get("state"));
    }

    @Test
    void rejectsTooManyNames() {
        var controller = new CapabilityProjectBusinessMethodStatesInternalController(
                mock(CapabilityScanProjectCatalogService.class), mock(BusinessMethodAssetMapper.class), mock(CapabilityDiffItemMapper.class));
        List<String> names = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "n" + i).toList();
        assertEquals(400, controller.states(7L, names).getStatusCode().value());
    }
}
