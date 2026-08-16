package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachAiRegistryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReachAiRegistryAutoConfiguration.class))
            .withBean(ReachAiRegistryTransport.class, NoopTransport::new)
            .withBean(ContractCapability.class)
            .withPropertyValues(
                    "reachai.registry.url=https://reachai.example.com",
                    "reachai.registry.app-key=demo-key",
                    "reachai.registry.app-secret=demo-secret",
                    "reachai.project.code=demo",
                    "reachai.project.name=Demo Project",
                    "reachai.embed.allowed-origins[0]=http://localhost:9200",
                    "reachai.embed.allowed-agent-ids[0]=team-archive-assistant",
                    "reachai.embed.token-ttl-seconds=1800");

    @Test
    void autoConfiguresReachAiPropertiesAndCapabilityScanner() {
        contextRunner.run(context -> {
            ReachAiRegistryProperties properties = context.getBean(ReachAiRegistryProperties.class);
            assertEquals("https://reachai.example.com", properties.getRegistry().getUrl());
            assertEquals("demo", properties.getProject().getCode());
            assertEquals("http://localhost:9200", properties.getEmbed().getAllowedOrigins().get(0));
            assertEquals("team-archive-assistant", properties.getEmbed().getAllowedAgentIds().get(0));
            assertEquals(1800, properties.getEmbed().getTokenTtlSeconds());
            assertEquals(ReachAiRegistryProperties.ScanMode.ALL_CONTROLLERS,
                    properties.getCapability().getScanMode());

            ReachCapabilityBeanScanner scanner = context.getBean(ReachCapabilityBeanScanner.class);
            List<ReachCapabilityDescriptor> descriptors = scanner.scan();
            assertEquals(1, descriptors.size());
            assertEquals("contract.query", descriptors.get(0).getName());
            assertEquals("query", descriptors.get(0).getMethodName());
            assertNotNull(context.getBean(ReachAiRegistryClient.class));
            assertNotNull(context.getBean(TaskScheduler.class));
            assertNotNull(context.getBean(ReachAiRegistryHeartbeatScheduler.class));
        });
    }

    @Test
    void publishesSpringBoot3AutoConfigurationImports() {
        String resource = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Spring Boot 3 auto-configuration imports must be packaged");
            try (Scanner scanner = new Scanner(input, "UTF-8").useDelimiter("\\A")) {
                String imports = scanner.hasNext() ? scanner.next() : "";
                assertTrue(imports.contains(ReachAiRegistryAutoConfiguration.class.getName()));
            }
        } catch (Exception ex) {
            throw new AssertionError("Unable to read Spring Boot 3 auto-configuration imports", ex);
        }
    }

    @Test
    void discoversAutoConfigurationThroughBootMetadata() {
        new ApplicationContextRunner()
                .withUserConfiguration(AutoConfigurationProbe.class)
                .withBean(ReachAiRegistryTransport.class, NoopTransport::new)
                .withBean(ContractCapability.class)
                .withPropertyValues(
                        "reachai.registry.url=https://reachai.example.com",
                        "reachai.registry.app-key=demo-key",
                        "reachai.registry.app-secret=demo-secret",
                        "reachai.project.code=demo")
                .run(context -> {
                    assertNotNull(context.getBean(ReachAiRegistryProperties.class));
                    assertNotNull(context.getBean(ReachAiRegistryClient.class));
                    assertNotNull(context.getBean(ReachCapabilityBeanScanner.class));
                });
    }

    @Test
    void annotatedOnlyModeSkipsUnannotatedSpringMvcEndpoints() {
        contextRunner
                .withBean(BusinessController.class)
                .withPropertyValues("reachai.capability.scan-mode=ANNOTATED_ONLY")
                .run(context -> {
                    ReachAiRegistryProperties properties = context.getBean(ReachAiRegistryProperties.class);
                    assertEquals(ReachAiRegistryProperties.ScanMode.ANNOTATED_ONLY,
                            properties.getCapability().getScanMode());

                    ReachCapabilityBeanScanner scanner = context.getBean(ReachCapabilityBeanScanner.class);
                    List<ReachCapabilityDescriptor> descriptors = scanner.scan();
                    assertEquals(1, descriptors.size());
                    assertEquals("contract.query", descriptors.get(0).getName());
                });
    }

    @Test
    void springMvcEndpointScanCanBeRestrictedByPackageConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ReachAiRegistryAutoConfiguration.class))
                .withBean(ReachAiRegistryTransport.class, NoopTransport::new)
                .withBean(BusinessController.class)
                .withBean(FrameworkController.class)
                .withPropertyValues(
                        "reachai.registry.url=https://reachai.example.com",
                        "reachai.registry.app-key=demo-key",
                        "reachai.registry.app-secret=demo-secret",
                        "reachai.project.code=demo",
                        "reachai.capability.scan-packages[0]=com.enterprise.ai.reach.spring",
                        "reachai.capability.exclude-packages[0]=" + FrameworkController.class.getName())
                .run(context -> {
                    ReachCapabilityBeanScanner scanner = context.getBean(ReachCapabilityBeanScanner.class);
                    List<ReachCapabilityDescriptor> descriptors = scanner.scan();
                    assertEquals(1, descriptors.size());
                    assertEquals("business_ping", descriptors.get(0).getName());
                });
    }

    @Test
    void heartbeatUsesReachAiSchedulerWhenAnotherTaskSchedulerExists() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ReachAiRegistryAutoConfiguration.class,
                        ConsulLikeTaskSchedulerConfiguration.class))
                .withBean(ReachAiRegistryTransport.class, NoopTransport::new)
                .withBean(ContractCapability.class)
                .withPropertyValues(
                        "reachai.registry.url=https://reachai.example.com",
                        "reachai.registry.app-key=demo-key",
                        "reachai.registry.app-secret=demo-secret",
                        "reachai.project.code=demo")
                .run(context -> {
                    assertNotNull(context.getBean("reachAiRegistryTaskScheduler", TaskScheduler.class));
                    assertNotNull(context.getBean("catalogWatchTaskScheduler", TaskScheduler.class));
                    assertNotNull(context.getBean(ReachAiRegistryHeartbeatScheduler.class));
                });
    }

    static class ContractCapability {
        @ReachCapability(name = "contract.query", title = "查询合同")
        public String query(@ReachParam(description = "合同编号", required = true) String contractNo) {
            return contractNo;
        }
    }

    @RestController
    @RequestMapping("/business")
    static class BusinessController {
        @GetMapping("/ping")
        public String ping() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping("/framework")
    static class FrameworkController {
        @GetMapping("/ping")
        public String ping() {
            return "ok";
        }
    }

    static class NoopTransport implements ReachAiRegistryTransport {
        @Override
        public String exchange(String method, String url, Map<String, String> headers, Object body) {
            return "{}";
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class AutoConfigurationProbe {
    }

    @Configuration
    static class ConsulLikeTaskSchedulerConfiguration {
        @Bean
        TaskScheduler catalogWatchTaskScheduler() {
            return new ConcurrentTaskScheduler();
        }
    }
}
