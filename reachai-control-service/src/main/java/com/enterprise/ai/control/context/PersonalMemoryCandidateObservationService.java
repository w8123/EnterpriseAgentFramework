package com.enterprise.ai.control.context;

import com.enterprise.ai.control.context.PersonalMemoryCandidateService.ObservationCommand;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Completed-turn extraction. Passive observations are asynchronous; explicit consent is synchronous. */
@Service
@Slf4j
public class PersonalMemoryCandidateObservationService {

    private final PersonalMemoryCandidateService candidateService;
    private final PersonalMemoryCandidateExtractor extractor;

    public PersonalMemoryCandidateObservationService(PersonalMemoryCandidateService candidateService,
                                                     PersonalMemoryCandidateExtractor extractor) {
        this.candidateService = candidateService;
        this.extractor = extractor;
    }

    @Async(PersonalMemoryAsyncConfiguration.CANDIDATE_EXECUTOR)
    public void observePassive(String identitySource,
                               String tenantId,
                               String runtimeUserId,
                               ObservationCommand command) {
        observeInternal(identitySource, tenantId, runtimeUserId, command);
    }

    /** Explicit consent must not be discarded when the passive queue is saturated. */
    public boolean observeExplicitIfPresent(String identitySource,
                                            String tenantId,
                                            String runtimeUserId,
                                            ObservationCommand command) {
        if (command == null) {
            return false;
        }
        if (!extractor.hasExplicitRememberSignal(command.userMessage())) {
            if (extractor.hasExplicitRememberPrefix(command.userMessage())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "explicit personal memory content is unsafe or invalid");
            }
            return false;
        }
        observeExplicit(identitySource, tenantId, runtimeUserId, command);
        return true;
    }

    private void observeExplicit(String identitySource,
                                 String tenantId,
                                 String runtimeUserId,
                                 ObservationCommand command) {
        if (!isTrustedSource(identitySource) || runtimeUserId == null || runtimeUserId.isBlank()) {
            return;
        }
        // Let failures reach the gateway. The Agent execution already completed, but returning a
        // success response that claims an explicit memory was retained would be misleading.
        candidateService.observe(principal(tenantId, runtimeUserId), command);
    }

    private void observeInternal(String identitySource,
                                 String tenantId,
                                 String runtimeUserId,
                                 ObservationCommand command) {
        if (!isTrustedSource(identitySource) || runtimeUserId == null || runtimeUserId.isBlank()) {
            return;
        }
        try {
            candidateService.observe(principal(tenantId, runtimeUserId), command);
        } catch (RuntimeException failure) {
            log.warn("Personal memory candidate extraction degraded: source={}, tenant={}, failureType={}",
                    identitySource, tenantId, failure.getClass().getSimpleName());
        }
    }

    private static PersonalMemoryPrincipal principal(String tenantId, String runtimeUserId) {
        return new PersonalMemoryPrincipal(
                tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim().toLowerCase(),
                runtimeUserId.trim(), null, null, null);
    }

    private static boolean isTrustedSource(String value) {
        return "AGENT".equalsIgnoreCase(value) || "EMBED_SESSION".equalsIgnoreCase(value);
    }
}
