package com.enterprise.ai.capability.catalog.httpapi;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HttpApiCatalogSourceConfirmationTest {
    @Test
    void starterInventoryIsConfirmedWithoutAnUnrelatedScanProjectRecord() throws Exception {
        Fixture fixture = fixture(HttpApiSourceKind.STARTER_MVC.name(), "inventory-1", "inventory-1");

        HttpApiCatalogService.ApiDetail detail = fixture.catalog.detail(9L);

        assertTrue(detail.summary().sourceConfirmed());
        assertEquals("DISCOVERED", detail.summary().sourceStatus());
        assertTrue(detail.sources().get(0).confirmedInLatestInventory());
    }

    @Test
    void scanSourceNeedsItsProjectAndLatestOperationMembership() throws Exception {
        Fixture absentProject = fixture(HttpApiSourceKind.OPENAPI_SCAN.name(), "inventory-1", "inventory-1");
        assertFalse(absentProject.catalog.detail(9L).summary().sourceConfirmed());
        assertEquals("SOURCE_UNCONFIRMED", absentProject.catalog.detail(9L).summary().sourceStatus());

        Fixture partial = fixture(HttpApiSourceKind.STARTER_MVC.name(), "inventory-2", "inventory-1");
        HttpApiCatalogService.ApiDetail detail = partial.catalog.detail(9L);
        assertFalse(detail.summary().sourceConfirmed());
        assertEquals("SOURCE_UNCONFIRMED", detail.summary().sourceStatus());
        assertFalse(detail.sources().get(0).confirmedInLatestInventory());
    }

    @Test
    void semanticMarketRevisionAndOwnerFactsRemainPinnedWithoutHashingObservationTimes() throws Exception {
        Fixture fixture = fixture(HttpApiSourceKind.STARTER_MVC.name(), "inventory-1", "inventory-1");
        String before = fixture.catalog.detail(9L).summary().sourceSetRevision();
        fixture.binding.setObservedAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        fixture.binding.setUpdatedAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        fixture.inventory.setObservedAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        fixture.member.setObservedAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        assertEquals(before, fixture.catalog.detail(9L).summary().sourceSetRevision());
        fixture.asset.setProjectCode("other-owner");
        assertNotEquals(before, fixture.catalog.detail(9L).summary().sourceSetRevision());
        fixture.asset.setProjectCode("orders");
        fixture.binding.setSourceKind(HttpApiSourceKind.API_MARKET_OPERATION.name());
        fixture.binding.setSourceRevision("fixed-version-operation-selection-1");
        String market = fixture.catalog.detail(9L).summary().sourceSetRevision();
        fixture.binding.setSourceRevision("fixed-version-operation-selection-2");
        assertNotEquals(market, fixture.catalog.detail(9L).summary().sourceSetRevision(), "market selection revision must remain a pin even before confirmation");
    }

    private Fixture fixture(String kind, String inventoryToken, String memberToken) throws Exception {
        HttpApiAssetMapper assets = mock(HttpApiAssetMapper.class);
        HttpApiSourceBindingMapper bindings = mock(HttpApiSourceBindingMapper.class);
        HttpApiInventoryStateMapper inventories = mock(HttpApiInventoryStateMapper.class);
        HttpApiInventoryMemberMapper members = mock(HttpApiInventoryMemberMapper.class);
        HttpApiAcceptanceMapper acceptances = mock(HttpApiAcceptanceMapper.class);
        ScanProjectMapper projects = mock(ScanProjectMapper.class);
        HttpApiAssetEntity asset = new HttpApiAssetEntity();
        asset.setId(9L); asset.setProjectId(7L); asset.setProjectCode("orders");
        asset.setEnvironment("dev"); asset.setHttpMethod("GET"); asset.setRouteTemplate("/orders/{id}");
        asset.setQualifiedName("orders.dev.GET./orders/{id}");
        HttpApiSourceBindingEntity binding = new HttpApiSourceBindingEntity();
        binding.setId(11L); binding.setAssetId(9L); binding.setProjectId(7L);
        binding.setProjectCode("orders"); binding.setEnvironment("dev");
        binding.setSourceKind(kind); binding.setSourceKey("source-1");
        binding.setStatus(HttpApiSourceBindingStatus.DISCOVERED.name());
        binding.setSourceContractJson("{\"sideEffect\":\"READ_ONLY\"}");
        binding.setSourceContractHash(HttpApiSqlTestFunctions.sha2(binding.getSourceContractJson(), 256));
        binding.setObservedAt(LocalDateTime.now());
        HttpApiInventoryStateEntity inventory = new HttpApiInventoryStateEntity();
        inventory.setProjectId(7L); inventory.setProjectCode("orders"); inventory.setEnvironment("dev"); inventory.setSourceKind(kind);
        inventory.setInventoryToken(inventoryToken); inventory.setSupported(true);
        inventory.setComplete(true); inventory.setObservedAt(LocalDateTime.now());
        HttpApiInventoryMemberEntity member = new HttpApiInventoryMemberEntity();
        member.setBindingId(11L); member.setInventoryToken(memberToken);
        member.setObservedAt(LocalDateTime.now());
        when(assets.selectById(9L)).thenReturn(asset);
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        when(inventories.selectList(any())).thenReturn(List.of(inventory));
        when(members.selectList(any())).thenReturn(List.of(member));
        HttpApiCatalogService catalog = new HttpApiCatalogService(assets, bindings, inventories, members,
                acceptances, projects, new ObjectMapper(), new HttpApiContractCanonicalizer(new ObjectMapper()));
        return new Fixture(catalog, asset, binding, inventory, member);
    }

    private record Fixture(HttpApiCatalogService catalog, HttpApiAssetEntity asset,
                           HttpApiSourceBindingEntity binding, HttpApiInventoryStateEntity inventory,
                           HttpApiInventoryMemberEntity member) { }
}
