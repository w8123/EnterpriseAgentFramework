package com.enterprise.ai.control.a2a.domain.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class A2aPublicationAddressTest {

    @Test
    void normalizesAHostBasedHttpsOrigin() {
        A2aPublicationAddress address = A2aPublicationAddress.of(
                "https://Agent.Example.com:8443/", "AGENT.EXAMPLE.COM:8443",
                A2aEnvironment.PRODUCTION);

        assertEquals("https://agent.example.com:8443", address.publicOrigin());
        assertEquals("agent.example.com:8443", address.publicHost());
    }

    @Test
    void rejectsProductionHttpAndOriginsWithPaths() {
        assertEquals("A2A_PRODUCTION_HTTPS_REQUIRED", assertThrows(A2aDomainException.class,
                () -> A2aPublicationAddress.of("http://agent.example.com",
                        "agent.example.com", A2aEnvironment.PRODUCTION)).code());
        assertEquals("A2A_PUBLIC_ORIGIN_INVALID", assertThrows(A2aDomainException.class,
                () -> A2aPublicationAddress.of("https://agent.example.com/custom",
                        "agent.example.com", A2aEnvironment.PRODUCTION)).code());
    }

    @Test
    void rejectsHostHeaderThatDoesNotMatchThePublishedOrigin() {
        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> A2aPublicationAddress.of("https://agent.example.com",
                        "other.example.com", A2aEnvironment.PRODUCTION));
        assertEquals("A2A_PUBLIC_HOST_MISMATCH", error.code());
    }
}
