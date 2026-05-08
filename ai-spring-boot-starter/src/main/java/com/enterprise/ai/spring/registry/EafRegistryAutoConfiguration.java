package com.enterprise.ai.spring.registry;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(EafRegistryProperties.class)
@ConditionalOnClass(RequestMappingHandlerMapping.class)
public class EafRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SdkDescriptionSourceSettingsHolder sdkDescriptionSourceSettingsHolder() {
        return new SdkDescriptionSourceSettingsHolder();
    }

    @Bean
    @ConditionalOnMissingBean
    public RuntimeCapabilityMetadataResolver runtimeCapabilityMetadataResolver(
            SdkDescriptionSourceSettingsHolder holder) {
        return new RuntimeCapabilityMetadataResolver(holder);
    }

    @Bean
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    @ConditionalOnMissingBean
    public EafCapabilityScanner eafCapabilityScanner(RequestMappingHandlerMapping mapping,
                                                     EafRegistryProperties properties,
                                                     RuntimeCapabilityMetadataResolver metadataResolver) {
        return new EafCapabilityScanner(mapping, properties, metadataResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public EafRegistryClient eafRegistryClient(EafRegistryProperties properties,
                                               EafCapabilityScanner scanner,
                                               SdkDescriptionSourceSettingsHolder descriptionSettingsHolder) {
        return new EafRegistryClient(properties, scanner, descriptionSettingsHolder);
    }

    @Bean
    @ConditionalOnMissingBean
    public EafAgentClient eafAgentClient(EafRegistryProperties properties) {
        return new EafAgentClient(properties);
    }

    @Bean
    public ApplicationRunner eafRegistryStartupRunner(EafRegistryClient client) {
        return args -> client.registerAndSync();
    }

    @Bean
    public EafRegistryHeartbeat eafRegistryHeartbeat(EafRegistryClient client,
                                                     EafRegistryProperties properties) {
        return new EafRegistryHeartbeat(client, properties);
    }

    @Bean
    public EafCapabilitiesEndpoint eafCapabilitiesEndpoint(EafRegistryClient client,
                                                           EafRegistryProperties properties) {
        return new EafCapabilitiesEndpoint(client, properties);
    }

    public static class EafRegistryHeartbeat {
        private final EafRegistryClient client;
        private final EafRegistryProperties properties;

        EafRegistryHeartbeat(EafRegistryClient client, EafRegistryProperties properties) {
            this.client = client;
            this.properties = properties;
        }

        @Scheduled(fixedDelayString = "${eaf.registry.heartbeat-interval-ms:30000}")
        public void heartbeat() {
            if (properties.getRegistry().isEnabled()) {
                client.heartbeat();
            }
        }

        @PreDestroy
        public void offline() {
            client.offline();
        }
    }

    @Endpoint(id = "eaf-capabilities")
    public static class EafCapabilitiesEndpoint {
        private final EafRegistryClient client;
        private final EafRegistryProperties properties;

        EafCapabilitiesEndpoint(EafRegistryClient client, EafRegistryProperties properties) {
            this.client = client;
            this.properties = properties;
        }

        @ReadOperation
        public List<EafCapabilityDescriptor> capabilities() {
            if (!properties.getCapability().isExposeActuatorEndpoint()) {
                return List.of();
            }
            return client.capabilities();
        }
    }
}
