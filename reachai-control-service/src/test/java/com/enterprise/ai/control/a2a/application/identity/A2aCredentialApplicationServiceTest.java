package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.application.port.A2aCredentialCipher;
import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialStatus;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aCredentialApplicationServiceTest {

    private final A2aCredentialRepository repository = mock(A2aCredentialRepository.class);
    private final A2aPrincipalRepository principals = mock(A2aPrincipalRepository.class);
    private final A2aRemoteAgentRepository remoteAgents = mock(A2aRemoteAgentRepository.class);
    private final A2aCredentialCipher cipher = mock(A2aCredentialCipher.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AtomicReference<byte[]> plaintextObservedByCipher = new AtomicReference<>();
    private A2aCredentialApplicationService service;

    @BeforeEach
    void setUp() {
        service = new A2aCredentialApplicationService(
                repository, principals, remoteAgents, cipher, passwordEncoder,
                new SecureRandom(), Clock.fixed(
                        Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
        when(cipher.encrypt(any(byte[].class), any(String.class))).thenAnswer(invocation -> {
            byte[] plaintext = invocation.getArgument(0);
            plaintextObservedByCipher.set(Arrays.copyOf(plaintext, plaintext.length));
            return new A2aCredentialCipher.EncryptedSecret(
                    "credential-key-v1", "nonce", "encrypted-material");
        });
        when(repository.save(any(A2aCredential.class))).thenAnswer(invocation -> {
            A2aCredential value = invocation.getArgument(0);
            return value.id() == null ? withId(value, value.rotatedFromId() == null ? 41L : 42L) : value;
        });
    }

    @Test
    void storesOutboundSecretOnlyAsCiphertextAndNeverReturnsIt() throws Exception {
        String rawSecret = "partner-bearer-secret-value";
        var result = service.storeOutboundSecret(
                new A2aCredentialContracts.StoreOutboundSecretRequest(
                        "partner-bearer", "Partner Bearer", "BEARER", rawSecret,
                        LocalDateTime.of(2026, 11, 1, 0, 0)),
                "operator");

        ArgumentCaptor<A2aCredential> saved = ArgumentCaptor.forClass(A2aCredential.class);
        verify(repository).save(saved.capture());
        assertEquals(A2aDirection.OUTBOUND, saved.getValue().direction());
        assertEquals("ENCRYPTED", saved.getValue().materialMode());
        assertEquals("encrypted-material", saved.getValue().materialCiphertext());
        assertNull(saved.getValue().materialHash());
        assertEquals(rawSecret,
                new String(plaintextObservedByCipher.get(), StandardCharsets.UTF_8));

        String responseJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertFalse(responseJson.contains(rawSecret));
        assertFalse(responseJson.contains("encrypted-material"));
        assertFalse(responseJson.contains("materialCiphertext"));
    }

    @Test
    void rotatesOutboundSecretRebindsRemoteAgentsAndRevokesTheOldVersion() {
        A2aCredential current = outboundCredential(41L, 1, null, A2aCredentialStatus.ACTIVE);
        when(repository.findById(41L)).thenReturn(Optional.of(current));
        when(repository.nextVersion("partner-bearer")).thenReturn(2);

        var result = service.rotateOutbound(41L,
                new A2aCredentialContracts.RotateOutboundSecretRequest(
                        "replacement-bearer-secret", null), "operator");

        assertEquals(42L, result.credential().id());
        assertEquals(2, result.credential().versionNo());
        verify(remoteAgents).rebindCredential(41L, 42L);
        ArgumentCaptor<A2aCredential> saves = ArgumentCaptor.forClass(A2aCredential.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saves.capture());
        assertTrue(saves.getAllValues().stream().anyMatch(value ->
                value.id() != null && value.id() == 41L
                        && value.status() == A2aCredentialStatus.REVOKED));
        verify(cipher).encrypt(any(byte[].class),
                eq("reachai:a2a:credential:outbound:partner-bearer:2"));
    }

    private A2aCredential outboundCredential(
            Long id, int version, Long rotatedFromId, A2aCredentialStatus status) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 0, 0);
        return new A2aCredential(
                id, "partner-bearer", "Partner Bearer", A2aDirection.OUTBOUND,
                A2aCredentialType.BEARER, "ENCRYPTED", null, "old-ciphertext",
                "credential-key-v1", "old-nonce", null, "a".repeat(64), version, status,
                now.minusDays(1), now.plusDays(90), null, rotatedFromId, "operator", now, now);
    }

    private A2aCredential withId(A2aCredential value, long id) {
        return new A2aCredential(
                id, value.credentialKey(), value.name(), value.direction(), value.credentialType(),
                value.materialMode(), value.materialHash(), value.materialCiphertext(),
                value.encryptionKeyId(), value.encryptionNonce(), value.externalRef(),
                value.fingerprint(), value.versionNo(), value.status(), value.notBefore(),
                value.expiresAt(), value.lastUsedAt(), value.rotatedFromId(), value.createdBy(),
                value.createdAt(), value.updatedAt());
    }
}
