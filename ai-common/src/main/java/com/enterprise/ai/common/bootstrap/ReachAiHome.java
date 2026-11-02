package com.enterprise.ai.common.bootstrap;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where an installation keeps its own files. All five services of one installation must
 * resolve the same directory, otherwise each would generate different secrets.
 *
 * <ul>
 *   <li>{@code REACHAI_HOME}: data directory (generated secrets); default {@code .reachai} next to
 *       the config file, so dev.sh and IDE runs from the same checkout share it, else {@code ~/.reachai}.</li>
 *   <li>{@code REACHAI_CONFIG_FILE}: the one file a user edits; default the first
 *       {@code reachai.env} found in the working directory, its parent, or {@code REACHAI_HOME}.</li>
 * </ul>
 */
final class ReachAiHome {

    static final String HOME_PROPERTY = "REACHAI_HOME";
    static final String CONFIG_FILE_PROPERTY = "REACHAI_CONFIG_FILE";
    static final String CONFIG_FILE_NAME = "reachai.env";
    static final String SECRETS_FILE_NAME = "secrets.properties";

    private ReachAiHome() {
    }

    static Path home(Environment environment) {
        String configured = environment.getProperty(HOME_PROPERTY);
        if (StringUtils.hasText(configured)) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        Path config = explicitOrNearbyConfigFile(environment);
        if (config != null && config.getParent() != null) {
            return config.getParent().resolve(".reachai");
        }
        return Paths.get(System.getProperty("user.home"), ".reachai").toAbsolutePath().normalize();
    }

    static Path secretsFile(Environment environment) {
        return home(environment).resolve(SECRETS_FILE_NAME);
    }

    /** Returns the user's config file, or null when there is none. */
    static Path configFile(Environment environment) {
        Path config = explicitOrNearbyConfigFile(environment);
        if (config != null) {
            return config;
        }
        Path inHome = home(environment).resolve(CONFIG_FILE_NAME);
        return Files.isRegularFile(inHome) ? inHome.normalize() : null;
    }

    private static Path explicitOrNearbyConfigFile(Environment environment) {
        String configured = environment.getProperty(CONFIG_FILE_PROPERTY);
        if (StringUtils.hasText(configured)) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        Path workingDirectory = Paths.get("").toAbsolutePath();
        Path[] candidates = {
                workingDirectory.resolve(CONFIG_FILE_NAME),
                workingDirectory.getParent() == null ? null : workingDirectory.getParent().resolve(CONFIG_FILE_NAME),
        };
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        return null;
    }
}
