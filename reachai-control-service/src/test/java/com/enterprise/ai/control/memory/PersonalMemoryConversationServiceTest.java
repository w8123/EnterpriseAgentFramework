package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryConversationService.ObservationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalMemoryConversationServiceTest {

    @Test
    void explicitRememberTakesEffectAtOnceInThePersonalLayer() {
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        PersonalMemoryConversationService service =
                new PersonalMemoryConversationService(memoryService, new PersonalMemoryRememberExtractor());

        assertTrue(service.rememberExplicitIfPresent(PersonalMemoryIdentityResolver.PersonalMemoryPrincipal.business("Tenant-1", "42", "erp"), command("请记住我住在青岛")));

        verify(memoryService).remember(argThat(owner -> owner.tenantId().equals("tenant-1")
                        && owner.runtimeUserId().equals("42") && owner.ownerDomain().equals("BUSINESS:erp")),
                argThat(command -> command.content().equals("我住在青岛")
                        && PersonalMemoryService.SOURCE_CONVERSATION.equals(command.source())
                        && command.projectCode() == null && command.groupId() == null));
    }

    @Test
    void unscopedEmbedCannotClaimAnExplicitMemoryWasSaved() {
        var memoryService = mock(PersonalMemoryService.class);
        var service = new PersonalMemoryConversationService(memoryService, new PersonalMemoryRememberExtractor());
        var error = assertThrows(ResponseStatusException.class,
                () -> service.rememberExplicitIfPresent("EMBED_SESSION", "tenant", "42", command("请记住我住在青岛")));
        assertEquals(503, error.getStatusCode().value());
        verify(memoryService, never()).remember(any(), any());
    }

    @Test
    void explicitRememberPropagatesPersistenceFailure() {
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        when(memoryService.remember(any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        PersonalMemoryConversationService service =
                new PersonalMemoryConversationService(memoryService, new PersonalMemoryRememberExtractor());

        assertThrows(IllegalStateException.class,
                () -> service.rememberExplicitIfPresent("AGENT", "default", "42", command("请记住我住在青岛")));
    }

    @Test
    void ordinaryStatementsAreNotRemembered() {
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        PersonalMemoryConversationService service =
                new PersonalMemoryConversationService(memoryService, new PersonalMemoryRememberExtractor());

        assertFalse(service.rememberExplicitIfPresent("AGENT", "default", "42", command("我住在青岛")));

        verify(memoryService, never()).remember(any(), any());
    }

    @Test
    void unsafeExplicitRememberFailsInsteadOfReturningFalseConfirmation() {
        PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
        PersonalMemoryConversationService service =
                new PersonalMemoryConversationService(memoryService, new PersonalMemoryRememberExtractor());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.rememberExplicitIfPresent("AGENT", "default", "42",
                        command("请记住 api_key=sk-abcdefghijklmnopqrstuvwxyz123456")));

        assertEquals(400, error.getStatusCode().value());
        verify(memoryService, never()).remember(any(), any());
    }

    private static ObservationCommand command(String message) {
        return new ObservationCommand(message, "好的", "s1", "t1", "a1");
    }
}
