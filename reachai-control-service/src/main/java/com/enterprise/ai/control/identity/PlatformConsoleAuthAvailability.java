package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Answers whether the configured console provider is genuinely usable. This
 * intentionally does not expose provider configuration or secrets.
 */
@Service
@RequiredArgsConstructor
public class PlatformConsoleAuthAvailability {

    public static final String NOT_CONFIGURED = "PLATFORM_AUTH_NOT_CONFIGURED";
    public static final String PROVIDER_NOT_IMPLEMENTED = "PLATFORM_AUTH_PROVIDER_NOT_IMPLEMENTED";

    private final PlatformAuthProperties authProperties;
    private final PlatformAuthProviderMapper authProviderMapper;

    public Availability current() {
        String provider = authProperties.getProvider() == null
                ? ""
                : authProperties.getProvider().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"LOCAL".equals(provider)) {
            return Availability.unavailable(PROVIDER_NOT_IMPLEMENTED);
        }
        if (!authProperties.localPasswordLoginEnabled()) {
            return Availability.unavailable(NOT_CONFIGURED);
        }
        PlatformAuthProviderEntity localProvider = authProviderMapper.selectOne(
                new LambdaQueryWrapper<PlatformAuthProviderEntity>()
                        .eq(PlatformAuthProviderEntity::getProviderCode, "LOCAL")
                        .last("limit 1"));
        if (localProvider == null || !"ACTIVE".equalsIgnoreCase(localProvider.getStatus())) {
            return Availability.unavailable(NOT_CONFIGURED);
        }
        return Availability.configured();
    }

    public record Availability(boolean available, String code) {

        static Availability configured() {
            return new Availability(true, "PLATFORM_AUTH_CONFIGURED");
        }

        static Availability unavailable(String code) {
            return new Availability(false, code);
        }
    }
}
