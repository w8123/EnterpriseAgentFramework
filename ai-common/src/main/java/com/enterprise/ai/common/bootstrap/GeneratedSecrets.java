package com.enterprise.ai.common.bootstrap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Secrets an installation generates for itself on first start, so nobody has to invent and
 * copy six random strings into five services. Stored once in {@code REACHAI_HOME/secrets.properties}
 * (owner read/write only); explicit configuration still overrides every entry.
 */
final class GeneratedSecrets {

    /** One generated value can feed several properties that must stay equal across services. */
    record Secret(String key, List<String> properties) {
    }

    static final List<Secret> SECRETS = List.of(
            new Secret("internal-service-secret", List.of("reachai.internal.service-secret")),
            new Secret("model-credential-secret", List.of("model.credential-secret")),
            new Secret("workflow-credential-secret", List.of("agent.workflow-credential-secret")),
            new Secret("embed-token-secret", List.of("eaf.embed-token.secret")),
            new Secret("ai-coding-task-secret-pepper", List.of("reachai.ai-coding-task.secret-pepper")),
            new Secret("personal-memory-index-identity-secret", List.of(
                    "reachai.context.personal-memory.index-identity-secret",
                    "reachai.personal-memory.index-identity-secret")));

    private static final SecureRandom RANDOM = new SecureRandom();

    private GeneratedSecrets() {
    }

    static boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    /** Reads the file and returns property name to value. */
    static Map<String, Object> load(Path file) throws IOException {
        Properties stored = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stored.load(reader);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Secret secret : SECRETS) {
            String value = stored.getProperty(secret.key());
            if (value != null && !value.isBlank()) {
                for (String property : secret.properties()) {
                    properties.put(property, value.trim());
                }
            }
        }
        return properties;
    }

    /** Creates the file with fresh random values unless it already exists. */
    static void createIfAbsent(Path file) throws IOException {
        if (exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        Properties generated = new Properties();
        for (Secret secret : SECRETS) {
            generated.setProperty(secret.key(), randomValue());
        }
        Path temporary = Files.createTempFile(file.getParent(), ".secrets", ".tmp");
        restrictToOwner(temporary);
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            generated.store(writer, "ReachAI generated secrets. Back this file up together with the database:"
                    + " encrypted credentials cannot be read without it. Do not share or commit it.");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the file stays under the user's own profile directory.
        }
    }
}
