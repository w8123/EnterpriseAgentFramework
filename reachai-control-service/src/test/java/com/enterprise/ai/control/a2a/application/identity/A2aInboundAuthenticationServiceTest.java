package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository.PublishedCard;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.application.publication.A2aPublishedCardService;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialStatus;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialType;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aAuthorizationPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDataPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDelegatedIdentityPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import com.enterprise.ai.control.a2a.domain.trust.A2aPersonalMemoryPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfileStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aInboundAuthenticationServiceTest {

    private A2aPublishedCardService cards;
    private A2aTrustProfileRepository trusts;
    private A2aCredentialRepository credentials;
    private A2aPrincipalRepository principals;
    private BCryptPasswordEncoder encoder;
    private A2aInboundAuthenticationService service;

    @BeforeEach
    void setUp() {
        cards = mock(A2aPublishedCardService.class);
        trusts = mock(A2aTrustProfileRepository.class);
        credentials = mock(A2aCredentialRepository.class);
        principals = mock(A2aPrincipalRepository.class);
        encoder = new BCryptPasswordEncoder(4);
        service = new A2aInboundAuthenticationService(cards, trusts, credentials, principals,
                encoder, Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC));
        when(cards.findByHost("agent.example.com")).thenReturn(Optional.of(publication()));
    }

    @Test
    void derivesStablePrincipalFromVersionedApiKeyAndNeverCreatesDelegatedUser() {
        String raw = "ra2a_partner-key.1.abcdefghijklmnopqrstuvwxyz0123456789";
        A2aTrustProfile trust = trust(false);
        A2aCredential credential = credential(raw);
        A2aPrincipal principal = principal(credential.id());
        when(trusts.findActiveById(10L)).thenReturn(Optional.of(trust));
        when(credentials.findUsableByKeyAndVersion(eq("partner-key"), eq(1), any()))
                .thenReturn(Optional.of(credential));
        when(principals.findActiveByCredential(20L, 10L)).thenReturn(Optional.of(principal));

        A2aInboundCallContext call = service.authenticate("agent.example.com", raw);

        assertEquals(7L, call.principal().principalId());
        assertEquals("partner-principal", call.principal().principalKey());
        assertEquals(A2aAuthenticationMethod.API_KEY, call.principal().authenticationMethod());
        assertNull(call.principal().delegatedUser());
        verify(credentials).markUsed(eq(20L), any(LocalDateTime.class));
        verify(principals).markAuthenticated(eq(7L), any(LocalDateTime.class));
    }

    @Test
    void returnsOneGenericFailureForUnknownOrMalformedKeys() {
        when(trusts.findActiveById(10L)).thenReturn(Optional.of(trust(false)));

        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> service.authenticate("agent.example.com", "metadata-user-id"));

        assertEquals("A2A_AUTHENTICATION_FAILED", error.code());
        assertEquals("A2A authentication failed", error.getMessage());
    }

    @Test
    void anonymousDevelopmentPublicationRequiresAnExplicitCredentiallessPrincipal() {
        A2aTrustProfile trust = trust(true);
        A2aPrincipal principal = new A2aPrincipal(
                8L, "anonymous-dev", A2aPrincipalType.REMOTE_AGENT, "Anonymous developer",
                "tenant-a", "anonymous:tenant-a", 10L, null, Set.of("a2a:message:send"),
                Map.of(), A2aPrincipalStatus.ACTIVE, null, 0, "test", "test", null, null);
        when(trusts.findActiveById(10L)).thenReturn(Optional.of(trust));
        when(principals.findActiveAnonymous(10L)).thenReturn(Optional.of(principal));

        A2aInboundCallContext call = service.authenticate("agent.example.com", null);

        assertEquals(A2aAuthenticationMethod.ANONYMOUS, call.principal().authenticationMethod());
        assertNull(call.principal().delegatedUser());
    }

    private PublishedCard publication() {
        return new PublishedCard(1L, 2L, "support-agent", "agent.example.com",
                "agent-1", 3L, 10L, "tenant-a", "DEVELOPMENT",
                List.of("text/plain"), List.of("text/plain"), List.of(),
                "{}", "0".repeat(64), LocalDateTime.of(2026, 8, 23, 10, 0));
    }

    private A2aTrustProfile trust(boolean anonymous) {
        return new A2aTrustProfile(
                10L, "partner-default", "Partner default", null, A2aDirection.INBOUND,
                A2aEnvironment.DEVELOPMENT, A2aTrustLevel.KNOWN,
                Set.of(anonymous ? A2aAuthenticationMethod.ANONYMOUS : A2aAuthenticationMethod.API_KEY),
                Set.of("a2a:message:send"),
                new A2aAuthorizationPolicy(Set.of("support-agent"), Set.of("*"), Set.of("*"),
                        Set.of("*"), Set.of("tenant-a")),
                new A2aDataPolicy(Set.of("INTERNAL"), true, true, false, 30),
                A2aDelegatedIdentityPolicy.DENY, A2aPersonalMemoryPolicy.DISABLED,
                60, 10, 1_048_576, 10_485_760, 300_000, anonymous,
                A2aTrustProfileStatus.ACTIVE, 0, "test", "test", null, null);
    }

    private A2aCredential credential(String raw) {
        return new A2aCredential(
                20L, "partner-key", "Partner key", A2aDirection.INBOUND,
                A2aCredentialType.API_KEY, "HASHED", encoder.encode(raw),
                null, null, null, null, "a".repeat(64),
                1, A2aCredentialStatus.ACTIVE, null, LocalDateTime.of(2026, 12, 1, 0, 0),
                null, null, "test", null, null);
    }

    private A2aPrincipal principal(long credentialId) {
        return new A2aPrincipal(
                7L, "partner-principal", A2aPrincipalType.REMOTE_AGENT, "Partner Agent",
                "tenant-a", "sha256:" + "a".repeat(64), 10L, credentialId,
                Set.of("a2a:message:send"), Map.of(), A2aPrincipalStatus.ACTIVE,
                null, 0, "test", "test", null, null);
    }
}
