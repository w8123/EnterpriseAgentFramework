package com.enterprise.ai.agent.agent;

import com.enterprise.ai.agent.agent.persist.AgentVersionEntity;
import com.enterprise.ai.agent.agent.persist.AgentVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentVersionServiceTest {

    private AgentVersionMapper versionMapper;
    private AgentDefinitionService definitionService;
    private AgentVersionService service;
    private AtomicLong idGenerator;
    private List<AgentVersionEntity> store;

    @BeforeEach
    void setUp() {
        versionMapper = mock(AgentVersionMapper.class);
        definitionService = mock(AgentDefinitionService.class);
        idGenerator = new AtomicLong(1);
        store = new ArrayList<>();

        // 模拟 insert 分配 id 并入 store
        when(versionMapper.insert(any(AgentVersionEntity.class))).thenAnswer(inv -> {
            AgentVersionEntity e = inv.getArgument(0);
            e.setId(idGenerator.getAndIncrement());
            store.add(e);
            return 1;
        });
        when(versionMapper.updateById(any(AgentVersionEntity.class))).thenAnswer(inv -> {
            AgentVersionEntity e = inv.getArgument(0);
            store.removeIf(s -> s.getId().equals(e.getId()));
            store.add(e);
            return 1;
        });
        when(versionMapper.selectById(any())).thenAnswer(inv -> {
            Object id = inv.getArgument(0);
            return store.stream().filter(v -> v.getId().equals(id)).findFirst().orElse(null);
        });
        when(versionMapper.listActive(anyString())).thenAnswer(inv -> {
            String agentId = inv.getArgument(0);
            return store.stream()
                    .filter(v -> agentId.equals(v.getAgentId()))
                    .filter(v -> "ACTIVE".equals(v.getStatus()))
                    .sorted((a, b) -> Integer.compare(
                            b.getRolloutPercent() == null ? 0 : b.getRolloutPercent(),
                            a.getRolloutPercent() == null ? 0 : a.getRolloutPercent()))
                    .toList();
        });
        when(versionMapper.selectOne(any())).thenReturn(null);

        AgentDefinition def = AgentDefinition.builder()
                .id("agent-1")
                .keySlug("demo")
                .name("demo-agent")
                .intentType("DEMO")
                .systemPrompt("prompt")
                .tools(Arrays.asList("t1", "t2"))
                .maxSteps(5)
                .build();
        when(definitionService.findById("agent-1")).thenReturn(Optional.of(def));

        service = new AgentVersionService(versionMapper, definitionService);
    }

    @Test
    void publishCreatesActiveVersionAndRetiresOld() {
        AgentVersionEntity v1 = service.publish("agent-1", "v1.0.0", 100, "first", "alice");
        assertEquals("ACTIVE", v1.getStatus());
        assertEquals(100, v1.getRolloutPercent());

        // 第二次 publish 应该把 v1 RETIRE
        AgentVersionEntity v2 = service.publish("agent-1", "v1.0.1", 100, "second", "bob");
        assertEquals("ACTIVE", v2.getStatus());
        AgentVersionEntity v1After = store.stream().filter(s -> "v1.0.0".equals(s.getVersion())).findFirst().orElseThrow();
        assertEquals("RETIRED", v1After.getStatus());
    }

    @Test
    void publishRejectsDuplicateVersion() {
        when(versionMapper.selectOne(any())).thenReturn(null).thenAnswer(inv ->
                store.stream().filter(v -> "v1.0.0".equals(v.getVersion())).findFirst().orElse(null));
        service.publish("agent-1", "v1.0.0", 100, "first", "alice");

        assertThrows(IllegalArgumentException.class,
                () -> service.publish("agent-1", "v1.0.0", 100, "dup", "alice"));
    }

    @Test
    void publishRejectsInvalidRollout() {
        assertThrows(IllegalArgumentException.class,
                () -> service.publish("agent-1", "v1.0.0", -1, "bad", "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> service.publish("agent-1", "v1.0.0", 101, "bad", "alice"));
    }

    @Test
    void rollbackResurrectsOldVersion() {
        AgentVersionEntity v1 = service.publish("agent-1", "v1.0.0", 100, "first", "alice");
        service.publish("agent-1", "v1.0.1", 100, "second", "bob");

        // 此时 v1 已被 RETIRED；回滚到 v1
        AgentVersionEntity rolled = service.rollback("agent-1", v1.getId(), "carol");
        assertEquals("ACTIVE", rolled.getStatus());
        assertEquals(100, rolled.getRolloutPercent());
        // v2 应该被 RETIRED
        AgentVersionEntity v2 = store.stream().filter(s -> "v1.0.1".equals(s.getVersion())).findFirst().orElseThrow();
        assertEquals("RETIRED", v2.getStatus());
    }

    @Test
    void rollbackRejectsUnknownVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> service.rollback("agent-1", 9999L, "x"));
    }

    @Test
    void resolveActiveSnapshotPicksByUserHash() {
        // 发布两个灰度：v1.0.0 10%, v1.0.1 90%
        service.publish("agent-1", "v1.0.0", 10, "canary", "alice");
        service.publish("agent-1", "v1.0.1", 90, "main",   "bob");

        // 因为 v1.0.0 rollout=10 入库后 v1.0.1 发布时也 rollout=90 < 100，
        // 两版本都保持 ACTIVE（见 publish 中"rollout==100 才 retire"的逻辑）
        long active = store.stream().filter(s -> "ACTIVE".equals(s.getStatus())).count();
        assertEquals(2L, active);

        AgentDefinition snap = service.resolveActiveSnapshot("agent-1", "user-abcdef");
        assertNotNull(snap);
        assertTrue(snap.getExtra() != null && snap.getExtra().containsKey("__version"),
                "快照应该带上 __version 元信息");
    }

    @Test
    void resolveActiveSnapshotReturnsNullWhenNoActive() {
        assertNull(service.resolveActiveSnapshot("agent-1", "user-xyz"));
    }
}
