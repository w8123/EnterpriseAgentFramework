package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilityCatalogStateCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rollbackRestoresNullableCatalogFieldsWithoutRestoringSourceAuthorityOrWriteTimes() throws Exception {
        LocalDateTime removedAt = LocalDateTime.of(2026, 9, 5, 10, 20);
        LocalDateTime writtenAt = removedAt.plusHours(1);
        ScanProjectToolEntity originalScan = new ScanProjectToolEntity();
        originalScan.setId(12L);
        originalScan.setProjectId(3L);
        originalScan.setName("orders");
        originalScan.setSource("SDK");
        originalScan.setSourceQualifiedName("project.orders");
        originalScan.setAssetType("BUSINESS_METHOD");
        originalScan.setEnabled(false);
        originalScan.setRemovedFromSource(true);
        originalScan.setRemovedAt(removedAt);
        originalScan.setGlobalToolDefinitionId(45L);
        ToolDefinitionEntity originalGlobal = new ToolDefinitionEntity();
        originalGlobal.setId(45L);
        originalGlobal.setName("orders");
        originalGlobal.setQualifiedName("project.orders");
        originalGlobal.setSource("SDK");
        originalGlobal.setSourceQualifiedName("project.orders");
        originalGlobal.setAssetType("BUSINESS_METHOD");
        originalGlobal.setEnabled(false);

        ObjectNode snapshot = (ObjectNode) objectMapper.readTree(
                CapabilityCatalogStateCodec.capture(objectMapper, originalScan, originalGlobal));
        ObjectNode scanState = (ObjectNode) snapshot.get("scanTool");
        ObjectNode globalState = (ObjectNode) snapshot.get("globalTool");
        for (ObjectNode state : new ObjectNode[]{scanState, globalState}) {
            assertFalse(state.has("sourceQualifiedName"));
            assertFalse(state.has("createTime"));
            assertFalse(state.has("updateTime"));
            state.put("sourceQualifiedName", "forged.source");
            state.put("createTime", removedAt.toString());
            state.put("updateTime", removedAt.toString());
        }

        ScanProjectToolEntity restoredScan = new ScanProjectToolEntity();
        restoredScan.setSourceQualifiedName("trusted.source");
        restoredScan.setCreateTime(writtenAt);
        restoredScan.setUpdateTime(writtenAt);
        restoredScan.setTitle("new title");
        restoredScan.setModuleId(99L);
        restoredScan.setEnabled(true);
        ToolDefinitionEntity restoredGlobal = new ToolDefinitionEntity();
        restoredGlobal.setSourceQualifiedName("trusted.source");
        restoredGlobal.setCreateTime(writtenAt);
        restoredGlobal.setUpdateTime(writtenAt);
        restoredGlobal.setDescription("new description");
        restoredGlobal.setEnabled(true);

        CapabilityCatalogStateCodec.restoreScanTool(restoredScan, scanState);
        CapabilityCatalogStateCodec.restoreGlobalTool(restoredGlobal, globalState);

        assertEquals("trusted.source", restoredScan.getSourceQualifiedName());
        assertEquals("trusted.source", restoredGlobal.getSourceQualifiedName());
        assertEquals(writtenAt, restoredScan.getCreateTime());
        assertEquals(writtenAt, restoredScan.getUpdateTime());
        assertEquals(writtenAt, restoredGlobal.getCreateTime());
        assertEquals(writtenAt, restoredGlobal.getUpdateTime());
        assertNull(restoredScan.getTitle());
        assertNull(restoredScan.getModuleId());
        assertNull(restoredGlobal.getDescription());
        assertFalse(restoredScan.getEnabled());
        assertFalse(restoredGlobal.getEnabled());
        assertTrue(restoredScan.getRemovedFromSource());
        assertEquals(removedAt, restoredScan.getRemovedAt());
        assertEquals(45L, restoredScan.getGlobalToolDefinitionId());
        assertEquals("BUSINESS_METHOD", restoredScan.getAssetType());
        assertEquals("BUSINESS_METHOD", restoredGlobal.getAssetType());
    }

    @Test
    void oldRollbackStateWithoutAssetTypeRestoresTheLegacyProjectionDefault() throws Exception {
        ObjectNode snapshot = (ObjectNode) objectMapper.readTree(CapabilityCatalogStateCodec.capture(
                objectMapper, new ScanProjectToolEntity(), new ToolDefinitionEntity()));
        ((ObjectNode) snapshot.get("scanTool")).remove("assetType");
        ((ObjectNode) snapshot.get("globalTool")).remove("assetType");

        ObjectNode normalized = (ObjectNode) CapabilityCatalogStateCodec.normalizeLegacyAssetTypes(snapshot);
        assertEquals("UNCLASSIFIED", normalized.get("scanTool").get("assetType").asText());
        assertEquals("UNCLASSIFIED", normalized.get("globalTool").get("assetType").asText());

        ScanProjectToolEntity scan = new ScanProjectToolEntity();
        ToolDefinitionEntity global = new ToolDefinitionEntity();
        CapabilityCatalogStateCodec.restoreScanTool(scan, snapshot.get("scanTool"));
        CapabilityCatalogStateCodec.restoreGlobalTool(global, snapshot.get("globalTool"));

        assertEquals("UNCLASSIFIED", scan.getAssetType());
        assertEquals("UNCLASSIFIED", global.getAssetType());
    }

    @Test
    void serializationFailureCannotSilentlyDisableTheReviewStateCheck() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        JsonProcessingException serializationFailure = new JsonProcessingException("snapshot failure") {};
        when(failingMapper.writeValueAsString(any())).thenThrow(serializationFailure);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CapabilityCatalogStateCodec.capture(failingMapper, null, null));

        assertSame(serializationFailure, failure.getCause());
        assertTrue(failure.getMessage().contains("不能创建可回滚的评审项"));
    }
}
