package com.enterprise.ai.capability.catalog.semantic;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubject;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubjectResolver;
import com.enterprise.ai.capability.catalog.enrichment.CapabilityCatalogGroupingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilitySemanticDocumentStoreGroupReadTest {
    @Test void groupReadPreservesStableIdentityCheckWithoutTakingProjectWriteLockOrResolvingAgain() {
        var docs = mock(SemanticDocMapper.class);
        var projects = mock(ScanProjectMapper.class);
        var subjects = mock(CatalogSubjectResolver.class);
        var grouping = mock(CapabilityCatalogGroupingService.class);
        var store = new CapabilitySemanticDocumentStore(docs, projects, subjects, grouping);
        var group = new CatalogSubject(CatalogSubject.GROUP, 4L, 7L, "group:4", "订单", "", "hash", false,
                "", "", List.of(), List.of());
        var doc = new SemanticDocEntity(); doc.setProjectId(7L); doc.setSubjectType(CatalogSubject.GROUP);
        doc.setSubjectId(4L); doc.setSubjectKey("group:4");
        when(docs.selectOne(any())).thenReturn(doc);
        assertSame(doc, store.findGroup(group).orElseThrow());
        verify(docs).selectOne(any());
        verifyNoInteractions(projects, subjects, grouping);
        doc.setSubjectKey("group:other");
        assertThrows(IllegalStateException.class, () -> store.findGroup(group));
        assertThrows(IllegalArgumentException.class, () -> store.findGroup(new CatalogSubject(CatalogSubject.PROJECT,
                7L, 7L, "project:orders", "订单", "", "hash", false, "", "", List.of(), List.of())));
    }
}
