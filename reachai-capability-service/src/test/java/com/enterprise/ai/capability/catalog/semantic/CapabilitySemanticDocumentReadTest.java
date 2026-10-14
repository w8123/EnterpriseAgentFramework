package com.enterprise.ai.capability.catalog.semantic;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.capability.catalog.enrichment.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilitySemanticDocumentReadTest {
    @Test void normalOwnerReadDoesNotLockResolveAdoptOrWriteEvenWhenThereAreSourceObservations() {
        var docs = mock(SemanticDocMapper.class); var projects = mock(ScanProjectMapper.class);
        var subjects = mock(CatalogSubjectResolver.class); var grouping = mock(CapabilityCatalogGroupingService.class);
        var store = new CapabilitySemanticDocumentStore(docs, projects, subjects, grouping);
        var source = new com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity(); source.setId(41L);
        var owner = new CatalogSubject("HTTP_API", 21L, 7L, "api:owner", "Owner", "", "hash", true,
                "GET", "/owner", List.of(), List.of(source));
        assertTrue(store.find(owner).isEmpty());
        verify(docs, times(1)).selectOne(any()); verifyNoMoreInteractions(docs);
        verifyNoInteractions(projects, subjects, grouping);
    }
}
