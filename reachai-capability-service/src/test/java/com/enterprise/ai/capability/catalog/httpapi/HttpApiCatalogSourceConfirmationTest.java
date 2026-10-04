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
    void starterInventoryIsConfirmedWithoutAnUnrelatedScanProjectRecord() {
        Fixture fixture = fixture(HttpApiSourceKind.STARTER_MVC.name(), "inventory-1", "inventory-1");

        HttpApiCatalogService.ApiDetail detail = fixture.catalog.detail(9L);

        assertTrue(detail.summary().sourceConfirmed());
        assertEquals("DISCOVERED", detail.summary().sourceStatus());
        assertTrue(detail.sources().get(0).confirmedInLatestInventory());
    }

    @Test
    void scanSourceNeedsItsProjectAndLatestOperationMembership() {
        Fixture absentProject = fixture(HttpApiSourceKind.OPENAPI_SCAN.name(), "inventory-1", "inventory-1");
        assertFalse(absentProject.catalog.detail(9L).summary().sourceConfirmed());
        assertEquals("SOURCE_UNCONFIRMED", absentProject.catalog.detail(9L).summary().sourceStatus());

        Fixture partial = fixture(HttpApiSourceKind.STARTER_MVC.name(), "inventory-2", "inventory-1");
        HttpApiCatalogService.ApiDetail detail = partial.catalog.detail(9L);
        assertFalse(detail.summary().sourceConfirmed());
        assertEquals("SOURCE_UNCONFIRMED", detail.summary().sourceStatus());
        assertFalse(detail.sources().get(0).confirmedInLatestInventory());
    }

    private Fixture fixture(String kind, String inventoryToken, String memberToken) {
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
        binding.setSourceContractHash("a".repeat(64)); binding.setSourceContractJson("{\"sideEffect\":\"READ_ONLY\"}");
        binding.setObservedAt(LocalDateTime.now());
        HttpApiInventoryStateEntity inventory = new HttpApiInventoryStateEntity();
        inventory.setInventoryToken(inventoryToken); inventory.setSupported(true);
        inventory.setComplete(true); inventory.setObservedAt(LocalDateTime.now());
        HttpApiInventoryMemberEntity member = new HttpApiInventoryMemberEntity();
        member.setBindingId(11L); member.setInventoryToken(memberToken);
        member.setObservedAt(LocalDateTime.now());
        when(assets.selectById(9L)).thenReturn(asset);
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        when(inventories.selectOne(any())).thenReturn(inventory);
        when(members.selectOne(any())).thenReturn(member);
        HttpApiCatalogService catalog = new HttpApiCatalogService(assets, bindings, inventories, members,
                acceptances, projects, new ObjectMapper());
        return new Fixture(catalog);
    }

    private record Fixture(HttpApiCatalogService catalog) { }
}
