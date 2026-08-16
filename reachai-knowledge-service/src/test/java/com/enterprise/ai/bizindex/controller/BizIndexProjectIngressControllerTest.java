package com.enterprise.ai.bizindex.controller;

import com.enterprise.ai.bizindex.domain.dto.BizBatchUpsertRequest;
import com.enterprise.ai.bizindex.service.BizIndexDataService;
import com.enterprise.ai.internalauth.KnowledgeProjectIngressAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BizIndexProjectIngressControllerTest {

    @Test
    void forwardsOnlyMatchingVerifiedProject() {
        BizIndexDataService service = mock(BizIndexDataService.class);
        BizIndexProjectIngressController controller = new BizIndexProjectIngressController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(KnowledgeProjectIngressAuthFilter.VERIFIED_PROJECT_ATTRIBUTE, "orders");
        BizBatchUpsertRequest batch = new BizBatchUpsertRequest();
        batch.setItems(List.of());

        controller.batch(request, "orders", "orders_idx", batch);

        verify(service).batchUpsertForProject("orders", "orders_idx", List.of());
    }

    @Test
    void rejectsControllerInvocationWithDifferentProjectAttribute() {
        BizIndexDataService service = mock(BizIndexDataService.class);
        BizIndexProjectIngressController controller = new BizIndexProjectIngressController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(KnowledgeProjectIngressAuthFilter.VERIFIED_PROJECT_ATTRIBUTE, "other");

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.delete(request, "orders", "orders_idx",
                        new BizIndexProjectIngressController.DeleteRequest("O-1")));

        assertEquals(403, failure.getStatusCode().value());
        verifyNoInteractions(service);
    }
}
