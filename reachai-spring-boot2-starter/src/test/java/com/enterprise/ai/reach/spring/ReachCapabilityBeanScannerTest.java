package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachCapabilityBeanScannerTest {

    @Test
    void directBeanScanSkipsNullAndInfrastructureButFindsExplicitCapabilities() {
        ReachCapabilityBeanScanner scanner = new ReachCapabilityBeanScanner(new Object[]{
                null,
                new ReachAiRegistryProperties(),
                new ExplicitService()
        });

        List<ReachCapabilityDescriptor> result = scanner.scan();

        assertEquals(1, result.size());
        assertEquals("explicit.op", result.get(0).getName());
    }

    @Test
    void directBeanScanSeparatesBusinessMethodsAndMvcOperationsIncludingDualDeclarations() {
        ReachCapabilityBeanScanner scanner = new ReachCapabilityBeanScanner(new Object[]{new DemoController()});

        ReachCapabilityBeanScanner.ScanResult result = scanner.scanResult();

        assertEquals(1, result.getBusinessMethods().size());
        ReachCapabilityDescriptor explicit = findByName(result.getBusinessMethods(), "explicit.demo");
        assertEquals("demo", explicit.getMethodName());
        assertEquals(2, result.getHttpApis().size());
        assertEquals(1, httpWithPath(result.getHttpApis(), "/demo").size());
        assertEquals("GET", httpWithPath(result.getHttpApis(), "/other").get(0).getHttpMethod());
    }

    @Test
    void scanBeansFalseReturnsEmptyWithoutScanningContextBeans() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("explicitService", ExplicitService.class);
        context.refresh();
        try {
            ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
            properties.getCapability().setScanBeans(false);
            ReachCapabilityBeanScanner scanner = new ReachCapabilityBeanScanner(context, properties);

            assertEquals(0, scanner.scan().size());
        } finally {
            context.close();
        }
    }

    @Test
    void annotatedOnlyKeepsExplicitCapabilitiesAndSuppressesMvcInference() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("mixedController", MixedController.class);
        context.refresh();
        try {
            ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
            properties.getCapability().setScanMode(ReachAiRegistryProperties.ScanMode.ANNOTATED_ONLY);
            ReachCapabilityBeanScanner scanner = new ReachCapabilityBeanScanner(context, properties);

            ReachCapabilityBeanScanner.ScanResult result = scanner.scanResult();

            assertEquals(1, result.getBusinessMethods().size());
            findByName(result.getBusinessMethods(), "mixed.explicit");
            assertEquals(0, result.getHttpApis().size());
        } finally {
            context.close();
        }
    }

    @Test
    void packageFiltersApplyOnlyToMvcInferenceAndIgnoreBlankEntries() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("includedController", IncludedController.class);
        context.registerBean("excludedController", ExcludedController.class);
        context.refresh();
        try {
            ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
            properties.getCapability().setScanPackages(Arrays.asList(null, "", "   ", "com.enterprise.ai.reach.spring"));
            properties.getCapability().setExcludePackages(Arrays.asList(ExcludedController.class.getName()));
            ReachCapabilityBeanScanner scanner = new ReachCapabilityBeanScanner(context, properties);

            ReachCapabilityBeanScanner.ScanResult result = scanner.scanResult();

            findByName(result.getBusinessMethods(), "included.explicit");
            findByName(result.getBusinessMethods(), "excluded.explicit");
            assertEquals(1, httpWithPath(result.getHttpApis(), "/inc").size());
            assertEquals(0, httpWithPath(result.getHttpApis(), "/exc").size());
            assertEquals(2, result.getBusinessMethods().size());
        } finally {
            context.close();
        }
    }

    @Test
    void usesAopTargetClassWhenScanningProxiedBeans() {
        ProxiedController target = new ProxiedController();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        Object proxy = factory.getProxy();

        ReachCapabilityBeanScanner scanner = new ReachCapabilityBeanScanner(new Object[]{proxy});

        List<ReachHttpApiDescriptor> result = scanner.scanHttpApis();

        assertEquals(1, result.size());
        assertTrue(result.get(0).getSourceLocation().contains(ProxiedController.class.getName()));
        assertEquals("/proxy", result.get(0).getEndpointPath());
    }

    private static ReachCapabilityDescriptor findByName(List<ReachCapabilityDescriptor> descriptors, String name) {
        for (ReachCapabilityDescriptor descriptor : descriptors) {
            if (name.equals(descriptor.getName())) {
                return descriptor;
            }
        }
        throw new AssertionError("No descriptor with name=" + name);
    }

    private static List<ReachHttpApiDescriptor> httpWithPath(List<ReachHttpApiDescriptor> descriptors, String endpointPath) {
        List<ReachHttpApiDescriptor> matches = new ArrayList<ReachHttpApiDescriptor>();
        for (ReachHttpApiDescriptor descriptor : descriptors) {
            if (endpointPath.equals(descriptor.getEndpointPath())) {
                matches.add(descriptor);
            }
        }
        return matches;
    }

    static class ExplicitService {
        @ReachCapability(name = "explicit.op")
        public String op() {
            return "";
        }
    }

    @RestController
    static class DemoController {
        @ReachCapability(name = "explicit.demo")
        @GetMapping("/demo")
        public String demo() {
            return "";
        }

        @GetMapping("/other")
        public String other() {
            return "";
        }
    }

    @RestController
    static class MixedController {
        @ReachCapability(name = "mixed.explicit")
        @GetMapping("/explicit")
        public String explicit() {
            return "";
        }

        @GetMapping("/plain")
        public String plain() {
            return "";
        }
    }

    @RestController
    static class IncludedController {
        @ReachCapability(name = "included.explicit")
        public String includedExplicit() {
            return "";
        }

        @GetMapping("/inc")
        public String inc() {
            return "";
        }
    }

    @RestController
    static class ExcludedController {
        @ReachCapability(name = "excluded.explicit")
        public String excludedExplicit() {
            return "";
        }

        @GetMapping("/exc")
        public String exc() {
            return "";
        }
    }

    @RestController
    static class ProxiedController {
        @GetMapping("/proxy")
        public String endpoint() {
            return "";
        }
    }
}
