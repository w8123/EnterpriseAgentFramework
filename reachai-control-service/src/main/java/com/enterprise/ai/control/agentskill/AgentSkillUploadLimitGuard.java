package com.enterprise.ai.control.agentskill;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** Keeps the advertised Skill package limit reachable through the multipart transport. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AgentSkillUploadLimitGuard {

    private static final long MIN_MULTIPART_OVERHEAD_BYTES = 64L * 1024L;

    private final AgentSkillPackageInspector packageInspector;
    private final MultipartProperties multipartProperties;

    public AgentSkillUploadLimitGuard(AgentSkillPackageInspector packageInspector,
                                      MultipartProperties multipartProperties) {
        this.packageInspector = packageInspector;
        this.multipartProperties = multipartProperties;
    }

    @PostConstruct
    void validate() {
        if (multipartProperties == null || !multipartProperties.getEnabled()) {
            throw new IllegalStateException(
                    "Spring multipart uploads must be enabled for the Agent Skill import API");
        }
        long maxPackageBytes = packageInspector.limits().maxPackageBytes();
        requireAtLeast(
                multipartProperties.getMaxFileSize(),
                maxPackageBytes,
                "spring.servlet.multipart.max-file-size",
                "reachai.skill.max-package-bytes");
        long minimumRequestBytes;
        try {
            minimumRequestBytes = Math.addExact(maxPackageBytes, MIN_MULTIPART_OVERHEAD_BYTES);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Agent Skill package limit is too large", overflow);
        }
        requireAtLeast(
                multipartProperties.getMaxRequestSize(),
                minimumRequestBytes,
                "spring.servlet.multipart.max-request-size",
                "the package limit plus multipart overhead");
    }

    private void requireAtLeast(DataSize configured,
                                long minimumBytes,
                                String property,
                                String requirement) {
        long configuredBytes = configured == null ? -1L : configured.toBytes();
        // Spring uses a negative DataSize for an unlimited transport setting.
        if (configuredBytes >= 0L && configuredBytes < minimumBytes) {
            throw new IllegalStateException(
                    property + " must be at least " + minimumBytes + " bytes to satisfy " + requirement);
        }
    }
}
