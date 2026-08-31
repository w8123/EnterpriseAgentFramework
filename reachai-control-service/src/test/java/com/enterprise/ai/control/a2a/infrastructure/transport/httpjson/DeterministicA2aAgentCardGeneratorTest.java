package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.application.port.A2aAgentCardGenerator;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.publication.A2aAgentCardSnapshot;
import com.enterprise.ai.control.a2a.domain.publication.A2aProtocolSkill;
import com.enterprise.ai.control.a2a.domain.trust.A2aAuthorizationPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDataPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDelegatedIdentityPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import com.enterprise.ai.control.a2a.domain.trust.A2aPersonalMemoryPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfileStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicA2aAgentCardGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DeterministicA2aAgentCardGenerator generator =
            new DeterministicA2aAgentCardGenerator(objectMapper);

    @Test
    void generatesAStableA2a10HttpJsonCardWithoutSecretsOrLegacyDiscriminators() throws Exception {
        A2aAgentCardGenerator.Source source = source();
        A2aAgentCardSnapshot first = generator.generate(source,
                profile(Set.of(A2aAuthenticationMethod.API_KEY), false));
        A2aAgentCardSnapshot second = generator.generate(source,
                profile(Set.of(A2aAuthenticationMethod.API_KEY), false));

        assertEquals(first.agentCardJson(), second.agentCardJson());
        assertEquals(first.agentCardSha256(), second.agentCardSha256());
        assertEquals(64, first.agentCardSha256().length());
        JsonNode card = objectMapper.readTree(first.agentCardJson());
        assertEquals("HTTP+JSON", card.at("/supportedInterfaces/0/protocolBinding").asText());
        assertEquals("1.0", card.at("/supportedInterfaces/0/protocolVersion").asText());
        assertEquals("https://agent.example.com/a2a/v1",
                card.at("/supportedInterfaces/0/url").asText());
        assertEquals("X-ReachAI-A2A-Key",
                card.at("/securitySchemes/reachaiApiKey/apiKeySecurityScheme/name").asText());
        assertFalse(card.at("/capabilities/streaming").asBoolean(true));
        assertFalse(first.agentCardJson().contains("\"kind\""));
        assertFalse(first.agentCardJson().toLowerCase().contains("credential"));
        assertFalse(first.agentCardJson().toLowerCase().contains("secret"));
    }

    @Test
    void omitsSecurityForAnExplicitDevelopmentAnonymousProfile() throws Exception {
        A2aAgentCardSnapshot snapshot = generator.generate(source(),
                profile(Set.of(A2aAuthenticationMethod.ANONYMOUS), true));
        JsonNode card = objectMapper.readTree(snapshot.agentCardJson());

        assertFalse(card.has("securitySchemes"));
        assertFalse(card.has("securityRequirements"));
    }

    @Test
    void refusesToAdvertiseAuthenticationThatLacksACompletePublicSchemeContract() {
        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> generator.generate(source(),
                        profile(Set.of(A2aAuthenticationMethod.HMAC), false)));

        assertEquals("A2A_AUTH_METHOD_NOT_ADVERTISABLE", error.code());
    }

    private A2aAgentCardGenerator.Source source() {
        return new A2aAgentCardGenerator.Source(
                "Support Agent",
                "Resolves customer support questions",
                "1.0.0",
                "ReachAI",
                "https://reachai.example.com",
                "https://docs.example.com/support-agent",
                null,
                "https://agent.example.com",
                "/a2a/v1",
                false,
                false,
                false,
                List.of("text/plain"),
                List.of("text/plain"),
                List.of(new A2aProtocolSkill(
                        "answer-question",
                        "Answer support question",
                        "Answers a support question using approved business capabilities",
                        List.of("support"),
                        List.of("Where is my order?"),
                        List.of("text/plain"),
                        List.of("text/plain"))));
    }

    private A2aTrustProfile profile(Set<A2aAuthenticationMethod> methods, boolean anonymous) {
        A2aEnvironment environment = anonymous ? A2aEnvironment.DEVELOPMENT : A2aEnvironment.PRODUCTION;
        return new A2aTrustProfile(
                1L, "support-partners", "Support partners", null,
                A2aDirection.INBOUND, environment,
                anonymous ? A2aTrustLevel.KNOWN : A2aTrustLevel.TRUSTED,
                methods, Set.of("support.read"),
                new A2aAuthorizationPolicy(Set.of("support-agent"),
                        Set.of("*"),
                        Set.of("answer-question"), Set.of("message:send"), Set.of("*")),
                new A2aDataPolicy(Set.of("INTERNAL"), true, false, false, 30),
                A2aDelegatedIdentityPolicy.DENY, A2aPersonalMemoryPolicy.DISABLED,
                60, 10, 1_048_576, 10_485_760, 300_000,
                anonymous, A2aTrustProfileStatus.ACTIVE, 0,
                "tester", "tester", null, null);
    }
}
