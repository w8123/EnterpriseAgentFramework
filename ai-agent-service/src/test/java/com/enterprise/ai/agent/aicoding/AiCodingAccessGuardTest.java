package com.enterprise.ai.agent.aicoding;

import com.enterprise.ai.agent.platform.control.auth.AiCodingKeyContext;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodingAccessGuardTest {

    @AfterEach
    void clearContext() {
        AiCodingKeyContext.clear();
    }

    @Test
    void requireProjectAccessWithExplicitKeyReturnsMatchedProject() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectService.matchesAiCodingAccessKey(7L, "rac_valid")).thenReturn(true);
        when(scanProjectService.getById(7L)).thenReturn(project);
        AiCodingAccessGuard guard = new AiCodingAccessGuard(scanProjectService);

        ScanProjectEntity result = guard.requireProjectAccess(7L, "  rac_valid  ");

        assertSame(project, result);
        verify(scanProjectService).matchesAiCodingAccessKey(7L, "rac_valid");
    }

    @Test
    void requireProjectAccessWithExplicitKeyRejectsBlankKey() {
        AiCodingAccessGuard guard = new AiCodingAccessGuard(mock(ScanProjectService.class));

        assertThrows(AiCodingUnauthorizedException.class, () -> guard.requireProjectAccess(7L, " "));
    }

    @Test
    void requireProjectAccessWithExplicitKeyRejectsInvalidKey() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, "wrong")).thenReturn(false);
        AiCodingAccessGuard guard = new AiCodingAccessGuard(scanProjectService);

        assertThrows(AiCodingAccessDeniedException.class, () -> guard.requireProjectAccess(7L, "wrong"));
    }
}
