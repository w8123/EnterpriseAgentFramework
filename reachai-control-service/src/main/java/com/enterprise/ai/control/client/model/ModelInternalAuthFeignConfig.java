package com.enterprise.ai.control.client.model;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.ModelServiceRequestAuth;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class ModelInternalAuthFeignConfig {
    @Bean
    RequestInterceptor modelInternalAuthInterceptor(
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String secret) {
        return template -> ModelServiceRequestAuth.sign(secret, InternalServiceAuthHeaders.CALLER_CONTROL,
                template.method(), ModelServiceRequestAuth.outboundTarget(
                        template.feignTarget() == null ? null : template.feignTarget().url(), template.url()),
                template.body()).forEach((name, value) -> {
                    template.removeHeader(name);
                    template.header(name, value);
                });
    }
}
