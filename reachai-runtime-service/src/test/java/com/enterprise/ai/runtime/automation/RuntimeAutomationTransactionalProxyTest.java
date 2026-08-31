package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeAutomationTransactionalProxyTest {

    @Test
    void automationTransactionalServicesCanBeClassProxiedWhenFeatureIsEnabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ProxyConfiguration.class);
            context.refresh();

            assertTrue(AopUtils.isCglibProxy(
                    context.getBean(RuntimeAutomationExecutionPersistenceService.class)));
            assertTrue(AopUtils.isCglibProxy(
                    context.getBean(RuntimeAutomationInteractionTerminationService.class)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ProxyConfiguration {

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        RuntimeAutomationExecutionPersistenceService executionPersistenceService() {
            return new RuntimeAutomationExecutionPersistenceService(
                    mock(RuntimeAutomationOccurrenceMapper.class),
                    mock(RuntimeAutomationAttemptMapper.class),
                    mock(RuntimeAutomationEventMapper.class),
                    mock(RuntimeAutomationJsonSupport.class));
        }

        @Bean
        RuntimeAutomationInteractionTerminationService interactionTerminationService() {
            return new RuntimeAutomationInteractionTerminationService(
                    mock(RuntimeInteractionSessionMapper.class),
                    mock(RuntimeInteractionEventMapper.class),
                    mock(RuntimeTraceSpanMapper.class),
                    mock(RuntimeAutomationJsonSupport.class));
        }
    }
}
