package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalMemoryIdentityAdminControllerTest {
    @Test void requiresGlobalPermissionBeforeAnyMutation() {
        var auth = mock(PlatformRequestAuthorization.class);
        var links = mock(PersonalMemoryIdentityLinkService.class);
        var request = new MockHttpServletRequest();
        var controller = new PersonalMemoryIdentityAdminController(auth, links);
        when(auth.requireGlobalPermission(request, PlatformPermissions.BUSINESS_USER_MANAGE))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.create(request,
                new PersonalMemoryIdentityAdminController.CreateCommand(1, 2, "核验资料"))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.revoke(request, 1,
                new PersonalMemoryIdentityAdminController.RevokeCommand("员工请求解除"))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.list(request, 1, 0)).getStatusCode().value());
        verifyNoInteractions(links);
    }
}
