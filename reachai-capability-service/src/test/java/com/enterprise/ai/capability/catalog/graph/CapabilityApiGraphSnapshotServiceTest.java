package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityApiGraphSnapshotServiceTest {

    @Test
    void readsApiGraphSnapshotFromCapabilityOwnedTables() {
        ApiGraphNodeMapper nodeMapper = mock(ApiGraphNodeMapper.class);
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        when(nodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(node()));
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(edge()));
        when(layoutMapper.selectList(any(Wrapper.class))).thenReturn(List.of(layout()));
        CapabilityApiGraphSnapshotService service =
                new CapabilityApiGraphSnapshotService(nodeMapper, edgeMapper, layoutMapper);

        CapabilityApiGraphSnapshotView snapshot = service.loadSnapshot(7L);

        assertEquals(1, snapshot.nodes().size());
        assertEquals("createOrder", snapshot.nodes().get(0).label());
        assertEquals(1, snapshot.edges().size());
        assertEquals("REQUEST_REF", snapshot.edges().get(0).kind());
        assertEquals("2026-06-30T04:00", snapshot.edges().get(0).confirmedAt());
        assertEquals(1, snapshot.layouts().size());
        assertEquals(10.0, snapshot.layouts().get(0).x());
    }

    private ApiGraphNodeEntity node() {
        ApiGraphNodeEntity entity = new ApiGraphNodeEntity();
        entity.setId(1L);
        entity.setProjectId(7L);
        entity.setKind("API");
        entity.setRefId(11L);
        entity.setLabel("createOrder");
        entity.setPropsJson("{}");
        return entity;
    }

    private ApiGraphEdgeEntity edge() {
        ApiGraphEdgeEntity entity = new ApiGraphEdgeEntity();
        entity.setId(2L);
        entity.setProjectId(7L);
        entity.setSourceNodeId(1L);
        entity.setTargetNodeId(3L);
        entity.setKind("REQUEST_REF");
        entity.setSource("manual");
        entity.setConfidence(1.0);
        entity.setStatus("CONFIRMED");
        entity.setConfirmedBy("tester");
        entity.setConfirmedAt(LocalDateTime.of(2026, 6, 30, 4, 0));
        entity.setEnabled(true);
        return entity;
    }

    private ApiGraphLayoutEntity layout() {
        ApiGraphLayoutEntity entity = new ApiGraphLayoutEntity();
        entity.setProjectId(7L);
        entity.setNodeId(1L);
        entity.setX(10.0);
        entity.setY(20.0);
        entity.setExtJson("{}");
        return entity;
    }
}
