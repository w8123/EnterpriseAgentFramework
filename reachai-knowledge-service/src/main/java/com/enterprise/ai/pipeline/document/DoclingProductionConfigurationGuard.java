package com.enterprise.ai.pipeline.document;

import com.enterprise.ai.common.internalauth.InternalTransportSecurityPolicy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Requires the internal Docling credential in production when the provider is enabled. */
@Component
public class DoclingProductionConfigurationGuard {

    public DoclingProductionConfigurationGuard(DoclingProperties properties, Environment environment) {
        boolean production = InternalTransportSecurityPolicy.isProduction(environment.getActiveProfiles())
                || InternalTransportSecurityPolicy.isProduction(environment.getDefaultProfiles());
        if (!production || !properties.isEnabled()) {
            return;
        }
        String apiKey = properties.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "REACHAI_DOCLING_API_KEY must be provided from a production Secret");
        }
    }
}
