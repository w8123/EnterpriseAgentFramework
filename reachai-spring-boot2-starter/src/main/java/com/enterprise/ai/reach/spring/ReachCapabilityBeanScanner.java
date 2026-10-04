package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityScanner;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans Spring beans for capabilities. When bean scanning is enabled, explicit
 * {@code @ReachCapability} declarations are retained while MVC endpoint
 * inference for {@code @RestController} beans is controlled by the registry
 * scan mode and package filters and resolved against the AOP target class.
 */
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
        return scanResult().getBusinessMethods();
    }

    /**
     * Produces separate inventories for declared business methods and inferred MVC operations.
     * The split is preserved until the registry wire payload so an HTTP operation cannot enter the
     * business-method projection path by accident.
     */
    public ScanResult scanResult() {
        List<ReachCapabilityDescriptor> descriptors = new ArrayList<ReachCapabilityDescriptor>();
        List<ReachHttpApiDescriptor> httpApis = new ArrayList<ReachHttpApiDescriptor>();
        if (properties != null && !properties.getCapability().isScanBeans()) {
            return new ScanResult(descriptors, httpApis);
        }
        if (directBeans != null) {
            for (Object bean : directBeans) {
                if (bean == null || isInfrastructureBean(bean)) {
                    continue;
                }
                Class<?> userClass = AopUtils.getTargetClass(bean);
                descriptors.addAll(ReachCapabilityScanner.scanClasses(userClass));
                if (shouldScanSpringMvcEndpoints(userClass)) {
                    httpApis.addAll(ReachSpringMvcEndpointScanner.scanClass(userClass));
                }
            }
            return new ScanResult(descriptors, httpApis);
        }
        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class);
        for (Object bean : beans.values()) {
            if (bean == null || isInfrastructureBean(bean)) {
                continue;
            }
            Class<?> userClass = AopUtils.getTargetClass(bean);
            descriptors.addAll(ReachCapabilityScanner.scanClasses(userClass));
            if (shouldScanSpringMvcEndpoints(userClass)) {
                httpApis.addAll(ReachSpringMvcEndpointScanner.scanClass(userClass));
            }
        }
        return new ScanResult(descriptors, httpApis);
    }

    public List<ReachHttpApiDescriptor> scanHttpApis() {
        return scanResult().getHttpApis();
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

    public static class ScanResult {
        private final List<ReachCapabilityDescriptor> businessMethods;
        private final List<ReachHttpApiDescriptor> httpApis;

        ScanResult(List<ReachCapabilityDescriptor> businessMethods, List<ReachHttpApiDescriptor> httpApis) {
            this.businessMethods = new ArrayList<ReachCapabilityDescriptor>(businessMethods);
            this.httpApis = new ArrayList<ReachHttpApiDescriptor>(httpApis);
        }

        public List<ReachCapabilityDescriptor> getBusinessMethods() {
            return new ArrayList<ReachCapabilityDescriptor>(businessMethods);
        }

        public List<ReachHttpApiDescriptor> getHttpApis() {
            return new ArrayList<ReachHttpApiDescriptor>(httpApis);
        }
    }
}
