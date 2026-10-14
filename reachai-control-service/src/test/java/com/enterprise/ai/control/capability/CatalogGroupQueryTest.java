package com.enterprise.ai.control.capability;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CatalogGroupQueryTest {
    @Test void ungroupedRequiresExplicitProjectAndStrictBooleanAndCannotCombineAGroup() {
        var request = new MockHttpServletRequest();
        assertFalse(CatalogGroupQuery.ungrouped(request, null));
        request.setParameter("ungrouped", "true");
        assertTrue(CatalogGroupQuery.ungrouped(request, 7L));
        assertThrows(ResponseStatusException.class, () -> CatalogGroupQuery.ungrouped(request, null));
        assertThrows(ResponseStatusException.class, () -> CatalogGroupQuery.ungrouped(request, 0L));
        request.setParameter("groupId", "4");
        assertThrows(ResponseStatusException.class, () -> CatalogGroupQuery.ungrouped(request, 7L));
        request.removeParameter("groupId");
        for (String invalid : new String[]{"", "1", "TRUE", "yes", " true"}) {
            request.setParameter("ungrouped", invalid);
            assertThrows(ResponseStatusException.class, () -> CatalogGroupQuery.ungrouped(request, 7L));
        }
        request.setParameter("ungrouped", "false");
        assertFalse(CatalogGroupQuery.ungrouped(request, 7L));
    }

    @Test void repeatedSelectionParametersAreRejectedRatherThanTakingFirstValue() {
        var request = new MockHttpServletRequest(); request.setParameter("ungrouped", "true", "false");
        assertThrows(ResponseStatusException.class, () -> CatalogGroupQuery.ungrouped(request, 7L));
        var duplicate = new MockHttpServletRequest(); duplicate.setParameter("groupId", "4", "5");
        assertThrows(ResponseStatusException.class, () -> CatalogGroupQuery.parse(duplicate, 7L));
    }
}
