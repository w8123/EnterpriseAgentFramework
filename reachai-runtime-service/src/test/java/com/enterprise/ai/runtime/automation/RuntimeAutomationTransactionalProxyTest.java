package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
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
                    mock(RuntimeAutomationJsonSupport.class),
                    mock(RuntimeAutomationExecutionSlotMapper.class),
                    mock(RuntimeRunLifecycleService.class),
                    mock(RuntimeTraceSpanTerminationService.class),
                    mock(RuntimeAutomationMapper.class));
        }

        @Bean
        RuntimeAutomationInteractionTerminationService interactionTerminationService() {
            return new RuntimeAutomationInteractionTerminationService(
                    mock(RuntimeWorkflowInteractionSessionService.class),
                    mock(RuntimeTraceSpanTerminationService.class));
        }
    }
}
