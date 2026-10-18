package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.graph.ReachAgentGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableConfigurationProperties(ReachAiRegistryProperties.class)
public class ReachAiRegistryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReachAiRegistryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ReachCapabilityBeanScanner reachCapabilityBeanScanner(ApplicationContext applicationContext,
                                                                 ReachAiRegistryProperties properties) {
        return new ReachCapabilityBeanScanner(applicationContext, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiRegistryTransport reachAiRegistryTransport(ReachAiRegistryProperties properties) {
        ReachAiHttpRegistryTransport transport = new ReachAiHttpRegistryTransport();
        transport.setConnectTimeoutMs(properties.getRegistry().getConnectTimeoutMs());
        transport.setReadTimeoutMs(properties.getRegistry().getReadTimeoutMs());
        return transport;
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiRegistryClient reachAiRegistryClient(ReachAiRegistryProperties properties,
                                                       ReachCapabilityBeanScanner capabilityBeanScanner,
                                                       ReachAiRegistryTransport transport) {
        return new ReachAiRegistryClient(properties, capabilityBeanScanner, transport);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiEmbedTokenClient reachAiEmbedTokenClient(
            ReachAiRegistryProperties properties,
            ReachAiRegistryTransport transport) {
        return new ReachAiEmbedTokenClient(properties, transport);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReachAiEmbedPrincipalResolver.class)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    public ReachAiEmbedTokenEndpoint reachAiEmbedTokenEndpoint(ReachAiEmbedPrincipalResolver principalResolver,
                                                               ReachAiEmbedTokenClient tokenClient) {
        return new ReachAiEmbedTokenEndpoint(principalResolver, tokenClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiAgentGraphClient reachAiAgentGraphClient(
            ReachAiRegistryProperties properties,
            ReachAiRegistryTransport transport) {
        return new ReachAiAgentGraphClient(properties, transport);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReachAiBusinessIndexClient reachAiBusinessIndexClient(
            ReachAiRegistryProperties properties,
            ReachAiRegistryTransport transport) {
        return new ReachAiBusinessIndexClient(properties, transport);
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
                                                          ReachAiRegistryClient registryClient,
                                                          ReachAiAgentGraphClient agentGraphClient,
                                                          ObjectProvider<ReachAgentGraph> agentGraphs) {
        return args -> {
            if (!properties.getRegistry().isEnabled()) {
                return;
            }
            registryClient.registerAndSync();
            List<ReachAgentGraph> graphs = agentGraphs.orderedStream().collect(Collectors.toList());
            if (properties.getAgentGraph().isSyncOnStartup() && !graphs.isEmpty()) {
                try {
                    agentGraphClient.sync(graphs);
                    log.info("[ReachAI Registry] synced {} agent graph(s) project={}",
                            graphs.size(), properties.getProject().getCode());
                } catch (Exception e) {
                    log.warn("[ReachAI Registry] agent graph sync failed project={} error={}",
                            properties.getProject().getCode(), e.toString());
                }
            }
        };
    }
}
