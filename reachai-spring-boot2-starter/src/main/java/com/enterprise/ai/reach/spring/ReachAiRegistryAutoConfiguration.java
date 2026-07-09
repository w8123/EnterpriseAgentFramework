package com.enterprise.ai.reach.spring;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;

@Configuration
@EnableConfigurationProperties(ReachAiRegistryProperties.class)
public class ReachAiRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReachCapabilityBeanScanner reachCapabilityBeanScanner(ApplicationContext applicationContext,
                                                                 ReachAiRegistryProperties properties) {
        return new ReachCapabilityBeanScanner(applicationContext, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiRegistryTransport reachAiRegistryTransport() {
        return new ReachAiHttpRegistryTransport();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiRegistryClient reachAiRegistryClient(ReachAiRegistryProperties properties,
                                                       ReachCapabilityBeanScanner capabilityBeanScanner,
                                                       ReachAiRegistryTransport transport) {
        return new ReachAiRegistryClient(properties, capabilityBeanScanner, transport);
    }

    @Bean
    @ConditionalOnMissingBean(name = "reachAiRegistryTaskScheduler")
    public TaskScheduler reachAiRegistryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("reachai-registry-heartbeat-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiRegistryHeartbeatScheduler reachAiRegistryHeartbeatScheduler(ReachAiRegistryProperties properties,
                                                                              ReachAiRegistryClient registryClient,
                                                                              @Qualifier("reachAiRegistryTaskScheduler") TaskScheduler taskScheduler) {
        return new ReachAiRegistryHeartbeatScheduler(properties, registryClient, taskScheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachCapabilityInvoker reachCapabilityInvoker(ApplicationContext applicationContext) {
        return new ReachCapabilityInvoker(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachCapabilityInvocationVerifier reachCapabilityInvocationVerifier(ReachAiRegistryProperties properties) {
        return new ReachCapabilityInvocationVerifier(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiRegistryRequestVerifier reachAiRegistryRequestVerifier(ReachAiRegistryProperties properties) {
        return new ReachAiRegistryRequestVerifier(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    public ReachCapabilityEndpoint reachCapabilityEndpoint(ReachCapabilityInvoker invoker,
                                                           ReachCapabilityInvocationVerifier invocationVerifier,
                                                           List<ReachAiSecurityContextBridge> securityContextBridges) {
        return new ReachCapabilityEndpoint(invoker, invocationVerifier, securityContextBridges);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    public ReachCapabilitySyncEndpoint reachCapabilitySyncEndpoint(ReachAiRegistryClient registryClient,
                                                                   ReachAiRegistryRequestVerifier requestVerifier) {
        return new ReachCapabilitySyncEndpoint(registryClient, requestVerifier);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.ControllerAdvice")
    public ReachAiInvocationExceptionHandler reachAiInvocationExceptionHandler() {
        return new ReachAiInvocationExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "reachAiRegistryStartupRunner")
    public ApplicationRunner reachAiRegistryStartupRunner(ReachAiRegistryProperties properties,
                                                          ReachAiRegistryClient registryClient) {
        return args -> {
            if (properties.getRegistry().isEnabled()) {
                registryClient.registerAndSync();
            }
        };
    }
}
