package com.enterprise.ai.capability.catalog.enrichment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogGroupSelectionTest {
    final CapabilityCatalogGroupingService grouping = mock(CapabilityCatalogGroupingService.class);
    final CatalogClassificationService classification = mock(CatalogClassificationService.class);
    final CatalogGroupSelection selection = new CatalogGroupSelection(grouping, classification);

    @Test void catalogFiltersValidateOnlyTheSelectedGroupAndNeverBuildACompleteIndex() {
        assertNull(selection.filter(null, null, false));
        assertEquals(new CatalogGroupSelection.Filter(7L, null, true), selection.filter(7L, null, true));
        assertEquals(new CatalogGroupSelection.Filter(7L, 4L, false), selection.filter(7L, 4L, false));
        verify(grouping).requireGroup(7L, 4L);
        verifyNoMoreInteractions(grouping); verifyNoInteractions(classification);
    }

    @Test void invalidForeignAndConflictingFiltersCannotBecomeAnUnfilteredCatalog() {
        assertThrows(IllegalArgumentException.class, () -> selection.filter(null, 4L, false));
        assertThrows(IllegalArgumentException.class, () -> selection.filter(7L, 0L, false));
        assertThrows(IllegalArgumentException.class, () -> selection.filter(7L, 4L, true));
        assertThrows(IllegalArgumentException.class, () -> selection.filter(null, null, true));
        when(grouping.requireGroup(7L, 99L)).thenThrow(new IllegalArgumentException("foreign"));
        assertThrows(IllegalArgumentException.class, () -> selection.filter(7L, 99L, false));
        verifyNoInteractions(classification);
    }
}
