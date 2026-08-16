package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live smoke against the configured development MySQL database.
 *
 * <p>Disabled unless {@code REACHAI_RUN_LIVE_ARTIFACT_SMOKE=true}. The test only creates
 * uniquely scoped context-canary artifacts, verifies the enterprise isolation/scrub boundary,
 * and removes its own metadata rows in {@code finally}.</p>
 */
@Tag("live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "REACHAI_RUN_LIVE_ARTIFACT_SMOKE", matches = "true")
class RuntimeToolResultArtifactLiveSmokeTest {

    @Autowired
    private RuntimeToolResultArtifactService artifactService;

    @Autowired
    private RuntimeToolResultArtifactMapper artifactMapper;

    @Test
    void encryptsScopesReadsAndScrubsAgainstConfiguredMysql() {
        long preexistingActive = artifactMapper.selectCount(
                Wrappers.<RuntimeToolResultArtifactEntity>lambdaQuery()
                        .eq(RuntimeToolResultArtifactEntity::getStatus,
                                RuntimeToolResultArtifactService.ACTIVE));
        Assumptions.assumeTrue(preexistingActive == 0,
                "live smoke requires no pre-existing ACTIVE tool-result artifacts");

        String runId = UUID.randomUUID().toString().replace("-", "");
        String agentName = "context-canary-agent";
        RuntimeContext owner = context("owner-" + runId, "session-clear-" + runId);
        RuntimeContext otherUser = context("other-" + runId, "session-clear-" + runId);
        RuntimeContext otherSession = context("owner-" + runId, "other-session-" + runId);
        List<Long> createdIds = new ArrayList<>();

        try {
            String content = "context-canary:" + runId + ":" + "0123456789abcdef".repeat(400);
            RuntimeToolResultArtifactService.ArtifactPointer clearPointer = artifactService.offload(
                            owner, agentName, "trace-clear-" + runId, "call-clear-" + runId,
                            "context_canary_tool", content)
                    .orElseThrow();
            RuntimeToolResultArtifactEntity clearEntity = find(clearPointer.artifactRef());
            createdIds.add(clearEntity.getId());

            assertEquals(content.length(), clearPointer.contentChars());
            assertFalse(Arrays.equals(
                    content.getBytes(StandardCharsets.UTF_8), clearEntity.getContentCiphertext()));
            assertFalse(clearEntity.getOwnerScopeHash().contains(runId));
            assertFalse(clearEntity.getSessionScopeHash().contains(runId));

            RuntimeToolResultArtifactService.ArtifactChunk first = artifactService.read(
                            owner, agentName, clearPointer.artifactRef(), 0, 257)
                    .orElseThrow();
            assertEquals(content.substring(0, 257), first.content());
            assertTrue(first.hasMore());
            assertTrue(artifactService.read(
                    otherUser, agentName, clearPointer.artifactRef(), 0, 257).isEmpty());
            assertTrue(artifactService.read(
                    otherSession, agentName, clearPointer.artifactRef(), 0, 257).isEmpty());

            artifactService.scrubSession(owner.getUserId(), owner.getSessionId(), agentName);
            RuntimeToolResultArtifactEntity cleared = find(clearPointer.artifactRef());
            assertEquals(RuntimeToolResultArtifactService.DELETED, cleared.getStatus());
            assertNull(cleared.getContentCiphertext());
            assertNull(cleared.getEncryptionNonce());
            assertNull(cleared.getActiveSlot());
            assertTrue(artifactService.read(
                    owner, agentName, clearPointer.artifactRef(), 0, 257).isEmpty());

            RuntimeContext expiringOwner = context("owner-" + runId, "session-expire-" + runId);
            RuntimeToolResultArtifactService.ArtifactPointer expiryPointer = artifactService.offload(
                            expiringOwner, agentName, "trace-expire-" + runId,
                            "call-expire-" + runId, "context_canary_tool", content)
                    .orElseThrow();
            RuntimeToolResultArtifactEntity expiryEntity = find(expiryPointer.artifactRef());
            createdIds.add(expiryEntity.getId());
            expiryEntity.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            artifactMapper.updateById(expiryEntity);

            assertTrue(artifactService.scrubExpired() >= 1);
            RuntimeToolResultArtifactEntity expired = find(expiryPointer.artifactRef());
            assertEquals(RuntimeToolResultArtifactService.EXPIRED, expired.getStatus());
            assertNull(expired.getContentCiphertext());
            assertNull(expired.getEncryptionNonce());
            assertNull(expired.getActiveSlot());
        } finally {
            createdIds.forEach(artifactMapper::deleteById);
        }
    }

    private RuntimeToolResultArtifactEntity find(String artifactRef) {
        RuntimeToolResultArtifactEntity entity = artifactMapper.selectOne(
                Wrappers.<RuntimeToolResultArtifactEntity>lambdaQuery()
                        .eq(RuntimeToolResultArtifactEntity::getArtifactRef, artifactRef)
                        .last("LIMIT 1"));
        if (entity == null) {
            throw new AssertionError("live smoke artifact not found: " + artifactRef);
        }
        return entity;
    }

    private static RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId("u:context-canary:" + userId)
                .sessionId("a:context-canary:s:" + sessionId)
                .build();
    }
}
