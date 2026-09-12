package com.enterprise.ai.reach.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Stores an automatically issued registry credential outside application YAML.
 * The Enrollment Token is deliberately never written to disk. Operators may
 * provide a managed secret path with reachai.registry.credential-store-path;
 * otherwise the process user's .reachai directory is used.
 */
final class ReachAiRegistryCredentialStore {

    private static final String KEY_APP_KEY = "appKey";
    private static final String KEY_APP_SECRET = "appSecret";
    private static final String KEY_PROJECT_CODE = "projectCode";

    private final ReachAiRegistryProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private IssuedCredential pendingCredential;

    ReachAiRegistryCredentialStore(ReachAiRegistryProperties properties) {
        this.properties = properties;
    }

    void loadIntoProperties() {
        if (credentialsConfigured()) {
            return;
        }
        Path path = credentialPath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            saved.load(input);
        } catch (Exception ignored) {
            return;
        }
        String configuredProject = trimmed(properties.getProject().getCode());
        String storedProject = trimmed(saved.getProperty(KEY_PROJECT_CODE));
        String appKey = trimmed(saved.getProperty(KEY_APP_KEY));
        String appSecret = trimmed(saved.getProperty(KEY_APP_SECRET));
        if (!StringUtils.hasText(configuredProject) || !configuredProject.equals(storedProject)
                || !StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
            return;
        }
        properties.getRegistry().setAppKey(appKey);
        properties.getRegistry().setAppSecret(appSecret);
    }

    /** Called under the client's registration lock before another network attempt. */
    void prepareForRegistration() {
        if (pendingCredential != null) {
            persistPendingCredential();
        }
        loadIntoProperties();
    }

    void persistFromRegistrationResponse(String responseBody) {
        try {
            JsonNode response = objectMapper.readTree(responseBody == null ? "{}" : responseBody);
            String appKey = text(response, KEY_APP_KEY);
            String appSecret = text(response, KEY_APP_SECRET);
            String projectCode = text(response, KEY_PROJECT_CODE);
            if (!StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)
                    || !StringUtils.hasText(projectCode)
                    || !projectCode.equals(trimmed(properties.getProject().getCode()))) {
                throw new IllegalStateException("registry enrollment response did not contain a matching issued credential");
            }
            pendingCredential = new IssuedCredential(projectCode, appKey, appSecret);
        } catch (Exception failure) {
            throw new IllegalStateException("ReachAI registry enrollment response did not contain a matching issued credential", failure);
        }
        persistPendingCredential();
    }

    private void persistPendingCredential() {
        IssuedCredential credential = pendingCredential;
        try {
            Properties stored = new Properties();
            stored.setProperty(KEY_PROJECT_CODE, credential.projectCode);
            stored.setProperty(KEY_APP_KEY, credential.appKey);
            stored.setProperty(KEY_APP_SECRET, credential.appSecret);
            Path target = credentialPath();
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                    stored.store(output, "ReachAI registry credential; do not commit or share");
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException noAtomicMove) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            properties.getRegistry().setAppKey(credential.appKey);
            properties.getRegistry().setAppSecret(credential.appSecret);
            properties.getRegistry().setEnrollmentToken(null);
            pendingCredential = null;
        } catch (Exception failure) {
            throw new IllegalStateException("ReachAI registry credential could not be persisted", failure);
        }
    }

    private static final class IssuedCredential {
        final String projectCode;
        final String appKey;
        final String appSecret;

        IssuedCredential(String projectCode, String appKey, String appSecret) {
            this.projectCode = projectCode;
            this.appKey = appKey;
            this.appSecret = appSecret;
        }
    }

    private boolean credentialsConfigured() {
        return StringUtils.hasText(properties.getRegistry().getAppKey())
                && StringUtils.hasText(properties.getRegistry().getAppSecret());
    }

    private Path credentialPath() {
        String configured = trimmed(properties.getRegistry().getCredentialStorePath());
        if (StringUtils.hasText(configured)) {
            return Paths.get(configured);
        }
        String project = trimmed(properties.getProject().getCode());
        String filename = (StringUtils.hasText(project) ? project : "default")
                .replaceAll("[^A-Za-z0-9._-]+", "-") + ".properties";
        return Paths.get(System.getProperty("user.home", "."), ".reachai", "registry-credentials", filename);
    }

    private static String text(JsonNode response, String field) {
        return response != null && response.hasNonNull(field) ? trimmed(response.path(field).asText()) : null;
    }

    private static String trimmed(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
