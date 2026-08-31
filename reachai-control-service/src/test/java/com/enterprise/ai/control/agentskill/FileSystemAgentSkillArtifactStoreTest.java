package com.enterprise.ai.control.agentskill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSystemAgentSkillArtifactStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesByDigestAndVerifiesEveryRead() throws Exception {
        FileSystemAgentSkillArtifactStore store = new FileSystemAgentSkillArtifactStore(
                temporaryDirectory.toString());
        byte[] archive = "portable-skill-zip".getBytes(StandardCharsets.UTF_8);
        String digest = AgentSkillPackageInspector.sha256(archive);

        AgentSkillArtifactStore.StoredArtifact first = store.put(digest, archive);
        AgentSkillArtifactStore.StoredArtifact second = store.put(digest, archive);

        assertEquals(first.artifactKey(), second.artifactKey());
        assertArrayEquals(archive, store.get(first.artifactKey(), digest));

        Path stored = store.root().resolve(first.artifactKey().replace('/', java.io.File.separatorChar));
        Files.writeString(stored, "tampered", StandardCharsets.UTF_8);
        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> store.get(first.artifactKey(), digest));
        assertEquals("SKILL_ARTIFACT_DIGEST_MISMATCH", failure.code());
    }

    @Test
    void rejectsUntrustedArtifactKeys() {
        FileSystemAgentSkillArtifactStore store = new FileSystemAgentSkillArtifactStore(
                temporaryDirectory.toString());

        AgentSkillException failure = assertThrows(AgentSkillException.class,
                () -> store.get("../outside.zip", "0".repeat(64)));

        assertEquals("SKILL_PACKAGE_INVALID", failure.code());
    }

    @Test
    void concurrentSameDigestWritersConvergeOnOneVerifiedArtifact() throws Exception {
        FileSystemAgentSkillArtifactStore store = new FileSystemAgentSkillArtifactStore(
                temporaryDirectory.toString());
        byte[] archive = "same-content-address-from-many-writers".getBytes(StandardCharsets.UTF_8);
        String digest = AgentSkillPackageInspector.sha256(archive);
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<AgentSkillArtifactStore.StoredArtifact>> tasks = new ArrayList<>();
            for (int index = 0; index < 18; index++) {
                tasks.add(() -> store.put(digest, archive));
            }
            List<String> keys = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get().artifactKey();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .distinct()
                    .toList();
            assertEquals(1, keys.size());
            assertArrayEquals(archive, store.get(keys.get(0), digest));
        } finally {
            executor.shutdownNow();
        }
    }
}
