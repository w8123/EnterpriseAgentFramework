package com.enterprise.ai.runtime.execution.capability;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogFeignClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityInternalAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RuntimeCapabilityGatewayWiringTest {

    @Test
    void runtimeComponentScanProvidesExactlyOneGuardedCatalogGateway() {
        RuntimeCapabilityCatalogFeignClient transport = mock(RuntimeCapabilityCatalogFeignClient.class);
        Map<String, Object> definition = Map.of("qualifiedName", "orders.find");
        when(transport.getToolDefinition("orders.find")).thenReturn(definition);

        new ApplicationContextRunner()
                .withUserConfiguration(CatalogGatewayScan.class)
                .withBean(RuntimeCapabilityCatalogFeignClient.class, () -> transport)
                .withBean(RuntimeCapabilityInternalAuthSigner.class,
                        () -> new RuntimeCapabilityInternalAuthSigner("test-only-internal-signing-key"))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RuntimeCapabilityCatalogClient.class);
                    RuntimeCapabilityCatalogClient client = context.getBean(RuntimeCapabilityCatalogClient.class);
                    assertThat(client).isInstanceOf(RuntimeCapabilityCatalogGateway.class);
                    assertThat(client.getToolDefinition("orders.find")).isSameAs(definition);
                    verify(transport).getToolDefinition("orders.find");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.enterprise.ai.runtime", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                    classes = CatalogClientComponentFilter.class))
    static class CatalogGatewayScan {
    }

    public static class CatalogClientComponentFilter implements TypeFilter {
        private final AssignableTypeFilter clientType = new AssignableTypeFilter(RuntimeCapabilityCatalogClient.class);

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return (reader.getAnnotationMetadata().hasAnnotation(Component.class.getName())
                    || reader.getAnnotationMetadata().hasMetaAnnotation(Component.class.getName()))
                    && clientType.match(reader, factory);
        }
    }
}
