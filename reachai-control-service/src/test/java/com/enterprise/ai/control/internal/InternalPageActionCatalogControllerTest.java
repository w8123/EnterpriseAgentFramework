package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.platform.PlatformPageActionRegistryEntity;
import com.enterprise.ai.control.platform.PlatformPageActionRegistryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalPageActionCatalogControllerTest {

    @Test
    void returnsPageActionCatalogEntryForRuntimeValidation() {
        PlatformPageActionRegistryMapper mapper = mock(PlatformPageActionRegistryMapper.class);
        PlatformPageActionRegistryEntity row = new PlatformPageActionRegistryEntity();
        row.setProjectCode("demo");
        row.setPageKey("orders");
        row.setActionKey("open");
        row.setStatus("ACTIVE");
        when(mapper.selectOne(any())).thenReturn(row);

        InternalPageActionCatalogController controller = new InternalPageActionCatalogController(mapper);

        ResponseEntity<InternalPageActionCatalogController.PageActionCatalogEntry> response =
                controller.getPageAction("demo", "orders", "open");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        InternalPageActionCatalogController.PageActionCatalogEntry body = response.getBody();
        assertNotNull(body);
        assertEquals("demo", body.projectCode());
        assertEquals("orders", body.pageKey());
        assertEquals("open", body.actionKey());
        assertEquals("ACTIVE", body.status());
    }

    @Test
    void returnsNotFoundWhenCatalogEntryDoesNotExist() {
        PlatformPageActionRegistryMapper mapper = mock(PlatformPageActionRegistryMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);

        InternalPageActionCatalogController controller = new InternalPageActionCatalogController(mapper);

        ResponseEntity<InternalPageActionCatalogController.PageActionCatalogEntry> response =
                controller.getPageAction("demo", "orders", "missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
