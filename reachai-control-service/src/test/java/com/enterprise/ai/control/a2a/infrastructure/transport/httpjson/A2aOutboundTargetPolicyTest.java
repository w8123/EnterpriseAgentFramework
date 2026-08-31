package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class A2aOutboundTargetPolicyTest {

    private final A2aHubProperties properties = new A2aHubProperties();
    private final A2aOutboundTargetPolicy policy = new A2aOutboundTargetPolicy(properties);

    @Test
    void rejectsLoopbackPrivateMetadataAndNonHttpsTargetsByDefault() {
        assertCode("A2A_REMOTE_NETWORK_FORBIDDEN", "https://127.0.0.1/agent-card.json");
        assertCode("A2A_REMOTE_NETWORK_FORBIDDEN", "https://10.0.0.8/agent-card.json");
        assertCode("A2A_REMOTE_NETWORK_FORBIDDEN", "https://169.254.169.254/latest/meta-data");
        assertCode("A2A_REMOTE_CARD_URL_INVALID", "http://8.8.8.8/agent-card.json");
        assertCode("A2A_REMOTE_CARD_PORT_INVALID", "https://8.8.8.8:8443/agent-card.json");
    }

    @Test
    void rejectsAmbiguousUrlComponentsAndDotSegmentsBeforeDns() {
        assertCode("A2A_REMOTE_CARD_URL_INVALID", "https://user@8.8.8.8/agent-card.json");
        assertCode("A2A_REMOTE_CARD_URL_INVALID", "https://8.8.8.8/agent-card.json?next=x");
        assertCode("A2A_REMOTE_CARD_PATH_INVALID", "https://8.8.8.8/a/../agent-card.json");
        assertCode("A2A_REMOTE_CARD_PATH_INVALID", "https://8.8.8.8/a/%2e%2e/agent-card.json");
    }

    @Test
    void keepsValidUtf8PercentEncodingCanonical() {
        URI normalized = policy.normalize(
                URI.create("https://8.8.8.8/cards/%E6%B1%89%E5%AD%97.json"));

        assertEquals("https://8.8.8.8/cards/%E6%B1%89%E5%AD%97.json",
                normalized.toASCIIString());
    }

    @Test
    void pinsAValidatedPublicLiteralAndAllowsPrivateOnlyWithExplicitSwitch() {
        var publicTarget = policy.validate(URI.create("https://8.8.8.8/agent-card.json"));
        assertEquals("8.8.8.8", publicTarget.host());
        assertEquals(1, publicTarget.addresses().length);

        properties.getOutbound().setPrivateNetworkAllowed(true);
        var privateTarget = policy.validate(URI.create("https://10.0.0.8/agent-card.json"));
        assertEquals("10.0.0.8", privateTarget.host());
    }

    private void assertCode(String expected, String url) {
        A2aDomainException failure = assertThrows(A2aDomainException.class,
                () -> policy.validate(URI.create(url)));
        assertEquals(expected, failure.code());
    }
}
