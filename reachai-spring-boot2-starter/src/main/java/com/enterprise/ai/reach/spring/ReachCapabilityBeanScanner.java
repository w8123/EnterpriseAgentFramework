package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityScanner;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReachCapabilityBeanScanner {

    private final ApplicationContext applicationContext;
    private final ReachAiRegistryProperties properties;
    private final Object[] directBeans;

    public ReachCapabilityBeanScanner(ApplicationContext applicationContext, ReachAiRegistryProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
        this.directBeans = null;
    }

    ReachCapabilityBeanScanner(Object[] directBeans) {
        this.applicationContext = null;
        this.properties = null;
        this.directBeans = directBeans;
    }

    public List<ReachCapabilityDescriptor> scan() {
        List<ReachCapabilityDescriptor> descriptors = new ArrayList<ReachCapabilityDescriptor>();
        if (properties != null && !properties.getCapability().isScanBeans()) {
            return descriptors;
        }
        if (directBeans != null) {
            for (Object bean : directBeans) {
                if (bean == null || isInfrastructureBean(bean)) {
                    continue;
                }
                Class<?> userClass = AopUtils.getTargetClass(bean);
                descriptors.addAll(ReachCapabilityScanner.scanClasses(userClass));
                if (shouldScanSpringMvcEndpoints(userClass)) {
                    descriptors.addAll(ReachSpringMvcEndpointScanner.scanClass(userClass));
                }
            }
            return descriptors;
        }
        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class);
        for (Object bean : beans.values()) {
            if (bean == null || isInfrastructureBean(bean)) {
                continue;
            }
            Class<?> userClass = AopUtils.getTargetClass(bean);
            descriptors.addAll(ReachCapabilityScanner.scanClasses(userClass));
            if (shouldScanSpringMvcEndpoints(userClass)) {
                descriptors.addAll(ReachSpringMvcEndpointScanner.scanClass(userClass));
            }
        }
        return descriptors;
    }

    private boolean shouldScanSpringMvcEndpoints(Class<?> userClass) {
        if (userClass == null || properties == null) {
            return true;
        }
        ReachAiRegistryProperties.Capability capability = properties.getCapability();
        if (ReachAiRegistryProperties.ScanMode.ANNOTATED_ONLY.equals(capability.getScanMode())) {
            return false;
        }
        String className = userClass.getName();
        if (matchesPackage(className, capability.getExcludePackages())) {
            return false;
        }
        List<String> scanPackages = capability.getScanPackages();
        return scanPackages == null || scanPackages.isEmpty() || matchesPackage(className, scanPackages);
    }

    private boolean matchesPackage(String className, List<String> packages) {
        if (className == null || packages == null) {
            return false;
        }
        for (String item : packages) {
            if (item == null) {
                continue;
            }
            String prefix = item.trim();
            if (prefix.isEmpty()) {
                continue;
            }
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isInfrastructureBean(Object bean) {
        String name = bean.getClass().getName();
        return name.startsWith("org.springframework.")
                || bean instanceof ReachCapabilityBeanScanner
                || bean instanceof ReachAiRegistryClient
                || bean instanceof ReachAiRegistryProperties
                || bean instanceof ReachAiRegistryAutoConfiguration
                || bean instanceof ReachAiRegistryHeartbeatScheduler
                || bean instanceof ReachCapabilityEndpoint
                || bean instanceof ReachCapabilitySyncEndpoint
                || bean instanceof ReachAiInvocationExceptionHandler;
    }
}
