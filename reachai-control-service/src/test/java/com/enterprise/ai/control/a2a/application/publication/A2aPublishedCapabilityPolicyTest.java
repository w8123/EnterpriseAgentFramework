package com.enterprise.ai.control.a2a.application.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class A2aPublishedCapabilityPolicyTest {

    private final A2aPublishedCapabilityPolicy policy = new A2aPublishedCapabilityPolicy();

    @Test
    void acceptsOnlyCapabilitiesImplementedByTheInboundAdapter() {
        assertDoesNotThrow(() -> policy.validate(revision(false, false, false,
                List.of("text/plain", "application/json", "text/uri-list"),
                List.of("text/plain"))));
    }

    @Test
    void rejectsCapabilitiesThatWouldMakeTheAgentCardDishonest() {
        A2aDomainException streaming = assertThrows(A2aDomainException.class,
                () -> policy.validate(revision(true, false, false,
                        List.of("text/plain"), List.of("text/plain"))));
        assertEquals("A2A_CAPABILITY_NOT_IMPLEMENTED", streaming.code());

        A2aDomainException output = assertThrows(A2aDomainException.class,
                () -> policy.validate(revision(false, false, false,
                        List.of("text/plain"), List.of("application/json"))));
        assertEquals("A2A_MEDIA_MODE_NOT_IMPLEMENTED", output.code());
    }

    private A2aPublicationContracts.RevisionDraftRequest revision(
            boolean streaming,
            boolean push,
            boolean extended,
            List<String> input,
            List<String> output) {
        return new A2aPublicationContracts.RevisionDraftRequest(
                11L, "1.0.0", "Agent", "Description", null, null, null, null,
                "https://agent.example.com", streaming, push, extended,
                input, output, List.of(new A2aPublicationContracts.ProtocolSkillRequest(
                "review", "Review", "Reviews a request", List.of("review"), List.of(),
                List.of("text/plain"), List.of("text/plain"))));
    }
}
