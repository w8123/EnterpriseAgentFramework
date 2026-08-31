package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.common.internalauth.InternalTransportSecurityPolicy;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Prevents production Runtime instances from materializing verified Skill packages in an ephemeral temp directory. */
@Component
public class RuntimeAgentSkillCacheProductionGuard {

    private final String configuredRoot;
    private final Environment environment;

    public RuntimeAgentSkillCacheProductionGuard(
            @Value("${reachai.runtime.skills.cache-directory:${user.home}/.reachai/runtime-skill-cache}")
            String configuredRoot,
            Environment environment) {
        this.configuredRoot = configuredRoot;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        if (!isProduction()) return;
        if (!StringUtils.hasText(configuredRoot)) {
            throw new IllegalStateException(
                    "reachai.runtime.skills.cache-directory must be configured to an absolute non-temporary path in production");
        }
        final Path configured;
        try {
            configured = Path.of(configuredRoot.trim());
        } catch (InvalidPathException exception) {
            throw new IllegalStateException(
                    "reachai.runtime.skills.cache-directory is not a valid filesystem path", exception);
        }
        if (!configured.isAbsolute()) {
            throw new IllegalStateException(
                    "reachai.runtime.skills.cache-directory must be an absolute path in production");
        }
        Path cacheRoot = configured.normalize();
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (cacheRoot.startsWith(temporaryRoot)) {
            throw new IllegalStateException(
                    "reachai.runtime.skills.cache-directory must not use the operating-system temp directory in production");
        }
    }

    private boolean isProduction() {
        if (environment == null) return false;
        return InternalTransportSecurityPolicy.isProduction(environment.getActiveProfiles())
                || InternalTransportSecurityPolicy.isProduction(environment.getDefaultProfiles());
    }
}
