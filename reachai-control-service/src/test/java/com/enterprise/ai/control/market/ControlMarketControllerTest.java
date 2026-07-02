package com.enterprise.ai.control.market;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlMarketControllerTest {

    @Test
    void managesMarketItemsWithoutFallingThroughToRetiredProxy() {
        ControlMarketItemMapper marketItemMapper = mock(ControlMarketItemMapper.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        CapabilityProxyClient capabilityClient = mock(CapabilityProxyClient.class);
        ControlMarketController controller = new ControlMarketController(
                new ControlMarketService(marketItemMapper, runtimeClient, capabilityClient),
                marketItemMapper);
        ControlMarketItemEntity listedItem = item(12L, "AGENT", "agent-1", "orders-agent", "PENDING_APPROVAL");
        when(marketItemMapper.selectList(any())).thenReturn(List.of(listedItem));
        when(runtimeClient.getAgent("agent-1")).thenReturn(ResponseEntity.ok(Map.of(
                "id", "agent-1",
                "keySlug", "orders-agent",
                "name", "Orders Agent",
                "description", "Order copilot",
                "projectId", 7L,
                "projectCode", "orders",
                "visibility", "SHARED",
                "tools", List.of("orders.search"))));
        when(capabilityClient.getToolDefinition("orders.search")).thenReturn(ResponseEntity.ok(Map.of(
                "qualifiedName", "orders.search",
                "kind", "TOOL")));
        when(marketItemMapper.selectById(12L)).thenReturn(listedItem);

        ResponseEntity<List<ControlMarketItemEntity>> listed = controller.list("AGENT", "PENDING_APPROVAL");
        ResponseEntity<?> submitted = controller.submitAgent("agent-1", new ControlMarketController.MarketSubmitRequest(
                null,
                "1.2.0",
                "jsh"));
        ResponseEntity<?> dependencyCheck = controller.dependencyCheck(12L);
        ResponseEntity<?> approved = controller.approve(12L, new ControlMarketController.MarketApproveRequest("lead"));
        ResponseEntity<?> exported = controller.exportPackage(12L);

        assertEquals(1, listed.getBody().size());
        ControlMarketItemEntity submittedItem = (ControlMarketItemEntity) submitted.getBody();
        assertEquals(HttpStatus.OK, submitted.getStatusCode());
        assertEquals("AGENT", submittedItem.getAssetKind());
        assertEquals("orders-agent", submittedItem.getAssetKey());
        assertEquals("1.2.0", submittedItem.getVersion());
        assertEquals(HttpStatus.OK, dependencyCheck.getStatusCode());
        assertEquals(List.of(), ((ControlMarketService.ImportCheckResult) dependencyCheck.getBody()).missing());
        assertEquals("LISTED", ((ControlMarketItemEntity) approved.getBody()).getStatus());
        assertNotNull(((Map<?, ?>) exported.getBody()).get("marketItem"));
        verify(marketItemMapper).insert(any());
        verify(marketItemMapper).updateById(listedItem);
    }

    @Test
    void submitsSkillThroughCapabilityLookupWithoutFallingThroughToRetiredProxy() {
        ControlMarketItemMapper marketItemMapper = mock(ControlMarketItemMapper.class);
        CapabilityProxyClient capabilityClient = mock(CapabilityProxyClient.class);
        ControlMarketController controller = new ControlMarketController(
                new ControlMarketService(marketItemMapper, mock(RuntimeProxyClient.class), capabilityClient),
                marketItemMapper);
        when(capabilityClient.getToolDefinition("billing.refund")).thenReturn(ResponseEntity.ok(Map.of(
                "id", 99L,
                "qualifiedName", "billing.refund",
                "name", "Refund",
                "description", "Refund capability",
                "kind", "SKILL",
                "projectId", 8L,
                "projectCode", "billing",
                "visibility", "PUBLIC",
                "specJson", "{\"steps\":[]}")));

        ResponseEntity<?> submitted = controller.submitSkill(new ControlMarketController.MarketSubmitRequest(
                "billing.refund",
                null,
                "jsh"));

        assertEquals(HttpStatus.OK, submitted.getStatusCode());
        ControlMarketItemEntity item = (ControlMarketItemEntity) submitted.getBody();
        assertEquals("SKILL", item.getAssetKind());
        assertEquals("billing.refund", item.getAssetKey());
        assertEquals("1.0.0", item.getVersion());
        verify(capabilityClient).getToolDefinition("billing.refund");
        verify(marketItemMapper).insert(any());
    }

    @Test
    void rejectsMarketSubmissionWhenAssetIsNotVisible() {
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlMarketController controller = new ControlMarketController(
                new ControlMarketService(mock(ControlMarketItemMapper.class), runtimeClient, mock(CapabilityProxyClient.class)),
                mock(ControlMarketItemMapper.class));
        when(runtimeClient.getAgent("agent-1")).thenReturn(ResponseEntity.ok(Map.of(
                "id", "agent-1",
                "keySlug", "orders-agent",
                "name", "Orders Agent",
                "visibility", "PRIVATE")));

        ResponseEntity<?> response = controller.submitAgent("agent-1", new ControlMarketController.MarketSubmitRequest(
                null,
                null,
                "jsh"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    private ControlMarketItemEntity item(Long id, String kind, String assetId, String assetKey, String status) {
        ControlMarketItemEntity item = new ControlMarketItemEntity();
        item.setId(id);
        item.setAssetKind(kind);
        item.setAssetId(assetId);
        item.setAssetKey(assetKey);
        item.setName(assetKey);
        item.setVersion("1.0.0");
        item.setVisibility("SHARED");
        item.setStatus(status);
        item.setDependencyManifestJson("{\"capabilities\":[{\"qualifiedName\":\"orders.search\"}]}");
        item.setSnapshotJson("{\"id\":\"agent-1\"}");
        return item;
    }
}
