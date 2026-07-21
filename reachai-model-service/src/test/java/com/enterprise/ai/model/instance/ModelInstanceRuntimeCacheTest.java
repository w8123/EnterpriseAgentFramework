package com.enterprise.ai.model.instance;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInstanceRuntimeCacheTest {

    @Test
    void clampsTtlToDefaultAndHardCap() {
        assertEquals(ModelInstanceRuntimeCache.DEFAULT_TTL_MS, ModelInstanceRuntimeCache.clampTtl(0));
        assertEquals(ModelInstanceRuntimeCache.DEFAULT_TTL_MS, ModelInstanceRuntimeCache.clampTtl(-1));
        assertEquals(ModelInstanceRuntimeCache.MAX_TTL_MS, ModelInstanceRuntimeCache.clampTtl(60_000L));
        assertEquals(5_000L, new ModelInstanceRuntimeCache(5_000L).ttlMs());
    }

    @Test
    void expiresEntriesAfterConfiguredTtl() throws Exception {
        ModelInstanceRuntimeCache cache = new ModelInstanceRuntimeCache(50L);
        ModelInstanceRuntime runtime = ModelInstanceRuntime.builder()
                .id("llm-1")
                .modelType("LLM")
                .protocol("OPENAI_COMPATIBLE")
                .connectionConfig(Map.of("baseUrl", "https://example.invalid"))
                .build();
        cache.put("llm-1", runtime);
        assertNotNull(cache.getIfPresent("llm-1"));
        Thread.sleep(80L);
        assertNull(cache.getIfPresent("llm-1"),
                "multi-instance stale window must end when TTL elapses");
    }

    @Test
    void localInvalidateRemovesEntryImmediately() {
        ModelInstanceRuntimeCache cache = new ModelInstanceRuntimeCache(30_000L);
        ModelInstanceRuntime runtime = ModelInstanceRuntime.builder()
                .id("llm-2")
                .modelType("LLM")
                .protocol("OPENAI_COMPATIBLE")
                .connectionConfig(Map.of("baseUrl", "https://example.invalid"))
                .build();
        cache.put("llm-2", runtime);
        cache.invalidate("llm-2");
        assertNull(cache.getIfPresent("llm-2"));
        assertTrue(cache.missCount() >= 1L);
    }
}
