package com.enterprise.ai.model.provider.mimo;

import com.enterprise.ai.model.provider.ModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 仅在配置了非空的 {@code model.mimo.api-key} 时注册 MiMo Provider。
 */
@Configuration
@EnableConfigurationProperties(MimoProperties.class)
public class MimoProviderConfiguration {

    static class MimoApiKeyPresentCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("model.mimo.api-key");
            return key != null && !key.isBlank();
        }
    }

    @Bean
    @Conditional(MimoApiKeyPresentCondition.class)
    public ModelProvider mimoProvider(MimoProperties properties, ObjectMapper objectMapper) {
        return new MimoProvider(properties, objectMapper);
    }
}
