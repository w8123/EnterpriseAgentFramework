package com.enterprise.ai.control.context;

import com.enterprise.ai.control.context.PersonalMemoryCandidateService.ObservationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalMemoryCandidateObservationServiceTest {

    @Test
    void explicitRememberIsSynchronousAndPropagatesPersistenceFailure() {
        PersonalMemoryCandidateService candidateService = mock(PersonalMemoryCandidateService.class);
        PersonalMemoryCandidateExtractor extractor = mock(PersonalMemoryCandidateExtractor.class);
        ObservationCommand command = command("请记住我住在青岛");
        when(extractor.hasExplicitRememberSignal(command.userMessage())).thenReturn(true);
        when(candidateService.observe(any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        PersonalMemoryCandidateObservationService service =
                new PersonalMemoryCandidateObservationService(candidateService, extractor);

        assertThrows(IllegalStateException.class,
                () -> service.observeExplicitIfPresent("AGENT", "default", "42", command));
    }

    @Test
    void passiveObservationUsesBestEffortFailureIsolation() {
        PersonalMemoryCandidateService candidateService = mock(PersonalMemoryCandidateService.class);
        PersonalMemoryCandidateExtractor extractor = mock(PersonalMemoryCandidateExtractor.class);
        when(candidateService.observe(any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        PersonalMemoryCandidateObservationService service =
                new PersonalMemoryCandidateObservationService(candidateService, extractor);

        service.observePassive("AGENT", "default", "42", command("我住在青岛"));

        verify(candidateService).observe(any(), any());
    }

    @Test
    void unsafeExplicitRememberFailsInsteadOfReturningFalseConfirmation() {
        PersonalMemoryCandidateService candidateService = mock(PersonalMemoryCandidateService.class);
        PersonalMemoryCandidateExtractor extractor = new PersonalMemoryCandidateExtractor();
        PersonalMemoryCandidateObservationService service =
                new PersonalMemoryCandidateObservationService(candidateService, extractor);
        ObservationCommand command = command("请记住 api_key=sk-abcdefghijklmnopqrstuvwxyz123456");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.observeExplicitIfPresent("AGENT", "default", "42", command));

        assertEquals(400, error.getStatusCode().value());
        verify(candidateService, never()).observe(any(), any());
    }

    private static ObservationCommand command(String message) {
        return new ObservationCommand(message, "好的", "s1", "t1", "a1", "AGENT", true);
    }
}
