package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.memory.PersonalMemoryCandidateService;
import com.enterprise.ai.control.memory.PersonalMemoryService;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbedPersonalMemoryControllerTest {

    private final PlatformEmbedTokenService tokenService = mock(PlatformEmbedTokenService.class);
    private final PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
    private final EmbedPersonalMemoryController controller = new EmbedPersonalMemoryController(
            tokenService, memoryService, mock(PersonalMemoryCandidateService.class));

    @Test
    void ownerComesOnlyFromTheVerifiedEmbedTokenLikeEmbeddedChat() {
        PlatformEmbedTokenClaims claims = new PlatformEmbedTokenClaims();
        claims.setTenantId("Tenant-A");
        claims.setExternalUserId(" erp-42 ");
        claims.setGlobalUserId("global-9");
        when(tokenService.verify("tok")).thenReturn(claims);
        when(memoryService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PersonalMemoryService.MemoryPage(List.of(), 0, 50, 0));

        var response = controller.list("Bearer tok", null, null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(memoryService).list(eq(new PersonalMemoryPrincipal("tenant-a", "erp-42", null, null, null)),
                any(), any(), any(), eq(50), eq(0));
    }

    @Test
    void missingOrInvalidTokenIsUnauthorizedAndTouchesNoMemory() {
        when(tokenService.verify("bad")).thenThrow(new PlatformEmbedTokenException("invalid"));

        assertEquals(HttpStatus.UNAUTHORIZED, controller.list(null, null, null, null, 50, 0).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.forget("Bearer bad", 1L).getStatusCode());
        verifyNoInteractions(memoryService);
    }

    @Test
    void anotherUsersMemoryStaysNotFound() {
        PlatformEmbedTokenClaims claims = new PlatformEmbedTokenClaims();
        claims.setExternalUserId("erp-42");
        when(tokenService.verify("tok")).thenReturn(claims);
        when(memoryService.forget(any(), eq(7L)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "personal memory not found"));

        var response = controller.forget("Bearer tok", 7L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().getCode());
    }
}
