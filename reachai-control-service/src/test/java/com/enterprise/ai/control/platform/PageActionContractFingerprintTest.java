package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageActionContractFingerprintTest {
    @Test
    void declarationIsStableAcrossObjectKeyOrderAndChangesForActualEffects() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ActionView action = mock(ActionView.class);
        PageView page = mock(PageView.class);
        when(action.id()).thenReturn(1L); when(action.pageId()).thenReturn(1L); when(page.id()).thenReturn(1L);
        when(action.inputSchema()).thenReturn(json.readTree("{\"b\":2,\"a\":{\"y\":1,\"x\":0}}"));
        String original = PageActionContractFingerprint.of(action, page, json);
        when(action.inputSchema()).thenReturn(json.readTree("{\"a\":{\"x\":0,\"y\":1},\"b\":2}"));
        assertEquals(original, PageActionContractFingerprint.of(action, page, json));
        when(action.implementationRef()).thenReturn("different-implementation");
        assertNotEquals(original, PageActionContractFingerprint.of(action, page, json));
        when(action.implementationRef()).thenReturn(null);
        when(page.routePattern()).thenReturn("/new-route");
        assertNotEquals(original, PageActionContractFingerprint.of(action, page, json));
        when(action.id()).thenReturn(null);
        assertNull(PageActionContractFingerprint.of(action, page, json));
    }
}
