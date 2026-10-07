package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilityCatalogStateCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void capturesDerivedStateWithoutSourceAuthorityOrWriteTimes() throws Exception {
        var removedAt = LocalDateTime.of(2026, 9, 5, 10, 20);
        var scan = new ScanProjectToolEntity();
        scan.setId(12L); scan.setProjectId(3L); scan.setName("orders");
        scan.setSourceQualifiedName("orders:query"); scan.setAssetType("BUSINESS_METHOD");
        scan.setEnabled(false); scan.setRemovedFromSource(true); scan.setRemovedAt(removedAt);
        scan.setCreateTime(removedAt); scan.setUpdateTime(removedAt);
        var global = new ToolDefinitionEntity();
        global.setId(45L); global.setProjectId(3L); global.setQualifiedName("orders:query");
        global.setSourceQualifiedName("orders:query"); global.setAssetType("BUSINESS_METHOD");
        global.setCreateTime(removedAt); global.setUpdateTime(removedAt);
        var state = objectMapper.readTree(CapabilityCatalogStateCodec.capture(objectMapper, scan, global));
        for (String key : new String[]{"scanTool", "globalTool"}) {
            assertFalse(state.get(key).has("sourceQualifiedName"));
            assertFalse(state.get(key).has("createTime"));
            assertFalse(state.get(key).has("updateTime"));
            assertEquals("BUSINESS_METHOD", state.get(key).get("assetType").asText());
        }
        assertEquals(12, state.get("scanTool").get("id").asLong());
        assertTrue(state.get("scanTool").get("removedFromSource").asBoolean());
        assertEquals(removedAt.toString(), state.get("scanTool").get("removedAt").asText());
    }

    @Test
    void missingOrCorruptedProjectionClassificationDoesNotInventAnAssetDefault() throws Exception {
        var scan = new ScanProjectToolEntity();
        var global = new ToolDefinitionEntity();
        global.setAssetType("CORRUPTED");
        var state = objectMapper.readTree(CapabilityCatalogStateCodec.capture(objectMapper, scan, global));
        assertTrue(state.get("scanTool").get("assetType").isNull());
        assertEquals("CORRUPTED", state.get("globalTool").get("assetType").asText());
        assertNull(scan.getAssetType());
        assertEquals("CORRUPTED", global.getAssetType());
    }

    @Test
    void serializationFailureCannotSilentlyDisableTheReviewStateCheck() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        JsonProcessingException failure = new JsonProcessingException("snapshot failure") {};
        when(failingMapper.writeValueAsString(any())).thenThrow(failure);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CapabilityCatalogStateCodec.capture(failingMapper, null, null));
        assertSame(failure, error.getCause());
        assertTrue(error.getMessage().contains("不能创建可回滚的评审项"));
    }
}
