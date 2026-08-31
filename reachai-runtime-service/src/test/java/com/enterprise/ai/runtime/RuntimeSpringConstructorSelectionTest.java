package com.enterprise.ai.runtime;

import com.enterprise.ai.runtime.api.RuntimePublicController;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient;
import com.enterprise.ai.runtime.debug.RuntimeExecutableDebugSessionService;
import com.enterprise.ai.runtime.managed.ManagedExecutionControlEventClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSpringConstructorSelectionTest {

    private final AutowiredAnnotationBeanPostProcessor processor =
            new AutowiredAnnotationBeanPostProcessor();

    @Test
    void selectsRuntimePublicControllerProductionConstructor() {
        assertSelectedConstructor(RuntimePublicController.class, 7);
    }

    @Test
    void selectsDebugSessionServiceProductionConstructor() {
        assertSelectedConstructor(RuntimeExecutableDebugSessionService.class, 5);
    }

    @Test
    void selectsModelStreamHttpClientProductionConstructor() {
        assertSelectedConstructor(RuntimeModelStreamHttpClient.class, 2);
    }

    @Test
    void selectsManagedExecutionControlEventClientProductionConstructor() {
        assertSelectedConstructor(ManagedExecutionControlEventClient.class, 3);
    }

    @Test
    void allRuntimeSpringComponentsHaveResolvableConstructors() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> failures = new ArrayList<>();

        scanner.findCandidateComponents("com.enterprise.ai.runtime").forEach(beanDefinition -> {
            String className = beanDefinition.getBeanClassName();
            try {
                Class<?> beanType = ClassUtils.forName(className, getClass().getClassLoader());
                Constructor<?>[] candidates = processor.determineCandidateConstructors(beanType, className);
                if (candidates == null) {
                    BeanUtils.getResolvableConstructor(beanType);
                } else if (candidates.length != 1) {
                    failures.add(className + " has " + candidates.length + " injection constructors");
                }
            } catch (Exception ex) {
                failures.add(className + ": " + ex.getMessage());
            }
        });

        assertTrue(failures.isEmpty(), () -> "Unresolvable Spring constructors: " + failures);
    }

    private void assertSelectedConstructor(Class<?> beanType, int expectedParameterCount) {
        Constructor<?>[] candidates = processor.determineCandidateConstructors(beanType, beanType.getName());

        assertNotNull(candidates, () -> "Spring found no injection constructor for " + beanType.getName());
        assertEquals(1, candidates.length, () -> "Spring found ambiguous constructors for " + beanType.getName());
        assertEquals(expectedParameterCount, candidates[0].getParameterCount());
    }
}
