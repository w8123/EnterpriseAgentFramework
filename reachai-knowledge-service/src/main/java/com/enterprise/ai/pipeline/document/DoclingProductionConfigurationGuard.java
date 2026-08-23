package com.enterprise.ai.pipeline.document;

import com.enterprise.ai.common.internalauth.InternalTransportSecurityPolicy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Prevents the discoverable local Docling credential from reaching production. */
@Component
public class DoclingProductionConfigurationGuard {

    static final String LOCAL_DEVELOPMENT_API_KEY = "local-dev-change-me";

    public DoclingProductionConfigurationGuard(DoclingProperties properties, Environment environment) {
        boolean production = InternalTransportSecurityPolicy.isProduction(environment.getActiveProfiles())
                || InternalTransportSecurityPolicy.isProduction(environment.getDefaultProfiles());
        if (!production || !properties.isEnabled()) {
            return;
        }
        String apiKey = properties.getApiKey();
        if (!StringUtils.hasText(apiKey) || LOCAL_DEVELOPMENT_API_KEY.equals(apiKey.trim())) {
            throw new IllegalStateException(
                    "REACHAI_DOCLING_API_KEY must be provided from a production Secret");
        }
    }
}
