package com.enterprise.ai.common.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSecretsTest {

    @Test
    void generatesOnceAndReusesTheSameValues(@TempDir Path home) throws Exception {
        Path file = home.resolve("nested").resolve(ReachAiHome.SECRETS_FILE_NAME);

        GeneratedSecrets.createIfAbsent(file);
        Map<String, Object> first = GeneratedSecrets.load(file);
        GeneratedSecrets.createIfAbsent(file);
        Map<String, Object> second = GeneratedSecrets.load(file);

        assertEquals(first, second);
        assertEquals(7, first.size());
        assertEquals(first.get("reachai.context.personal-memory.index-identity-secret"),
                first.get("reachai.personal-memory.index-identity-secret"));
        assertNotEquals(first.get("model.credential-secret"), first.get("agent.workflow-credential-secret"));
        assertTrue(String.valueOf(first.get("reachai.internal.service-secret")).length() >= 40);
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }
}
