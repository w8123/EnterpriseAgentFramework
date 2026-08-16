package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.runtime.supervisor.RuntimeContextEngineeringProperties;
import io.agentscope.core.agent.RuntimeContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeToolResultArtifactServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "runtime-tool-artifact-test"),
                RuntimeToolResultArtifactEntity.class);
    }

    @Test
    void encryptsScopesAndReadsOnlyFromTheOwningSessionInBoundedChunks() {
        RuntimeContextEngineeringProperties properties = properties(secret());
        RuntimeToolResultArtifactMapper mapper = mock(RuntimeToolResultArtifactMapper.class);
        AtomicReference<RuntimeToolResultArtifactEntity> stored = new AtomicReference<>();
        AtomicInteger postInsertSelections = new AtomicInteger();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            RuntimeToolResultArtifactEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            stored.set(entity);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            RuntimeToolResultArtifactEntity entity = stored.get();
            if (entity == null) {
                return null;
            }
            // First post-insert selection is the owning-scope read; the next simulates the
            // database rejecting a query built with a different owner hash.
            return postInsertSelections.incrementAndGet() == 1 ? entity : null;
        });

        RuntimeToolResultArtifactCipher cipher =
                new RuntimeToolResultArtifactCipher(properties);
        RuntimeToolResultArtifactService service =
                new RuntimeToolResultArtifactService(mapper, cipher, properties);
        RuntimeContext owner = RuntimeContext.builder()
                .userId("u:owner-hash")
                .sessionId("a:agent-hash:s:session-hash")
                .build();

        RuntimeToolResultArtifactService.ArtifactPointer pointer = service.offload(
                        owner, "orders-agent", "trace-1", "call-1", "query_orders", "abcdefghij")
                .orElseThrow();

        assertTrue(pointer.artifactRef().matches("tra_[a-f0-9]{32}"));
        assertFalse(Arrays.equals(
                "abcdefghij".getBytes(StandardCharsets.UTF_8),
                stored.get().getContentCiphertext()));
        assertFalse(stored.get().getOwnerScopeHash().contains("owner"));
        assertFalse(stored.get().getSessionScopeHash().contains("session"));
        assertEquals(1, stored.get().getActiveSlot());

        RuntimeToolResultArtifactService.ArtifactChunk first = service.read(
                        owner, "orders-agent", pointer.artifactRef(), 0, 4)
                .orElseThrow();
        assertEquals("abcd", first.content());
        assertEquals(4, first.nextOffset());
        assertTrue(first.hasMore());

        RuntimeContext otherUser = RuntimeContext.builder()
                .userId("u:other-owner")
                .sessionId("a:agent-hash:s:session-hash")
                .build();
        assertTrue(service.read(otherUser, "orders-agent", pointer.artifactRef(), 0, 4).isEmpty());

        service.scrubSession("u:owner-hash", "a:agent-hash:s:session-hash", "orders-agent");
        verify(mapper).update(isNull(), any());

        // The ACTIVE-only dedupe slot is released by scrub, so a later replay can safely
        // create a fresh encrypted artifact instead of colliding with the erased row.
        stored.set(null);
        RuntimeToolResultArtifactService.ArtifactPointer replayed = service.offload(
                        owner, "orders-agent", "trace-2", "call-1", "query_orders", "abcdefghij")
                .orElseThrow();
        assertFalse(pointer.artifactRef().equals(replayed.artifactRef()));
        verify(mapper, times(2)).insert(any());
    }

    @Test
    void authenticatedDataPreventsCiphertextFromBeingMovedAcrossScopes() {
        RuntimeContextEngineeringProperties properties = properties(secret());
        RuntimeToolResultArtifactCipher cipher = new RuntimeToolResultArtifactCipher(properties);
        RuntimeToolResultArtifactCipher.EncryptedPayload encrypted = cipher.encrypt(
                "sensitive-result".getBytes(StandardCharsets.UTF_8), "scope-a");

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyId(), "scope-b"));
    }

    @Test
    void expiredActiveDedupeSlotIsScrubbedBeforeCreatingAFreshArtifact() {
        RuntimeContextEngineeringProperties properties = properties(secret());
        RuntimeToolResultArtifactMapper mapper = mock(RuntimeToolResultArtifactMapper.class);
        RuntimeToolResultArtifactEntity expired = new RuntimeToolResultArtifactEntity();
        expired.setId(42L);
        expired.setArtifactRef("tra_00000000000000000000000000000000");
        expired.setActiveSlot(1);
        expired.setStatus(RuntimeToolResultArtifactService.ACTIVE);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        expired.setEncryptionNonce(new byte[12]);
        expired.setContentCiphertext(new byte[]{1});
        when(mapper.selectOne(any())).thenReturn(expired);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            RuntimeToolResultArtifactEntity inserted = invocation.getArgument(0);
            inserted.setId(43L);
            return 1;
        });
        RuntimeToolResultArtifactService service = new RuntimeToolResultArtifactService(
                mapper, new RuntimeToolResultArtifactCipher(properties), properties);
        RuntimeContext owner = RuntimeContext.builder()
                .userId("u:owner-hash")
                .sessionId("a:agent-hash:s:session-hash")
                .build();

        RuntimeToolResultArtifactService.ArtifactPointer pointer = service.offload(
                        owner, "orders-agent", "trace-2", "call-1", "query_orders", "fresh")
                .orElseThrow();

        assertFalse(expired.getArtifactRef().equals(pointer.artifactRef()));
        verify(mapper).update(isNull(), any());
        verify(mapper).insert(any());
    }

    @Test
    void enablingOffloadWithoutEnterpriseSecretFailsAtStartupBoundary() {
        assertThrows(IllegalStateException.class,
                () -> new RuntimeToolResultArtifactCipher(properties("short")));
    }

    @Test
    void disabledArtifactStoreDoesNotTouchItsTableDuringSessionClear() {
        RuntimeToolResultArtifactMapper mapper = mock(RuntimeToolResultArtifactMapper.class);
        RuntimeContextEngineeringProperties properties = new RuntimeContextEngineeringProperties(
                false,
                true,
                true,
                24,
                24_000,
                10,
                2,
                60_000,
                false,
                80_000,
                2_000,
                16_777_216,
                12_000,
                24,
                200,
                60_000,
                "test-key",
                "");
        RuntimeToolResultArtifactService service = new RuntimeToolResultArtifactService(
                mapper, new RuntimeToolResultArtifactCipher(properties), properties);

        service.scrubSession("u:key", "a:key:s:key", "orders-agent");
        assertEquals(0, service.scrubExpired());
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    private static RuntimeContextEngineeringProperties properties(String secret) {
        return new RuntimeContextEngineeringProperties(
                true,
                true,
                true,
                24,
                24_000,
                10,
                8,
                60_000,
                true,
                80_000,
                2_000,
                16_777_216,
                12_000,
                24,
                200,
                60_000,
                "test-key",
                secret);
    }

    private static String secret() {
        return "0123456789abcdef0123456789abcdef";
    }
}
