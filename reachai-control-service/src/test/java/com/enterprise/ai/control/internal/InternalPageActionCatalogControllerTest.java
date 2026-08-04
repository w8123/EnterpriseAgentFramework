package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalPageActionCatalogControllerTest {

    @Test
    void returnsPageActionCatalogEntryForRuntimeValidation() {
        PageCatalogApplicationService pageCatalog = mock(PageCatalogApplicationService.class);
        ActionView row = mock(ActionView.class);
        when(row.projectCode()).thenReturn("demo");
        when(row.pageKey()).thenReturn("orders");
        when(row.actionKey()).thenReturn("open");
        when(row.status()).thenReturn("ACTIVE");
        when(pageCatalog.findAction("demo", "orders", "open"))
                .thenReturn(java.util.Optional.of(row));

        InternalPageActionCatalogController controller =
                new InternalPageActionCatalogController(pageCatalog);

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
        PageCatalogApplicationService pageCatalog = mock(PageCatalogApplicationService.class);
        when(pageCatalog.findAction("demo", "orders", "missing"))
                .thenReturn(java.util.Optional.empty());

        InternalPageActionCatalogController controller =
                new InternalPageActionCatalogController(pageCatalog);

        ResponseEntity<InternalPageActionCatalogController.PageActionCatalogEntry> response =
                controller.getPageAction("demo", "orders", "missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void listsBoundPageActionsForRuntimeAiCodingCatalog() {
        PageCatalogApplicationService pageCatalog = mock(PageCatalogApplicationService.class);
        ActionView row = mock(ActionView.class);
        when(row.projectCode()).thenReturn("orders");
        when(row.pageKey()).thenReturn("orders.list");
        when(row.actionKey()).thenReturn("search");
        when(row.status()).thenReturn("ACTIVE");
        when(pageCatalog.listActions("orders", "orders.list", "ACTIVE", 100))
                .thenReturn(List.of(row));
        InternalPageActionCatalogController controller =
                new InternalPageActionCatalogController(pageCatalog);

        ResponseEntity<List<InternalPageActionCatalogController.PageActionCatalogEntry>> response =
                controller.list("orders", "orders.list", "ACTIVE", 100);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("search", response.getBody().get(0).actionKey());
        verify(pageCatalog).listActions("orders", "orders.list", "ACTIVE", 100);
    }
}
