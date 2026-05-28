package com.enterprise.ai.spring.registry;

import com.enterprise.ai.skill.SideEffectLevel;
import com.enterprise.ai.skill.annotation.AiTool;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EafCapabilityScannerAiToolAnnotationTest {

    @Test
    void scansAiToolAnnotationAsSdkToolDescriptor() throws Exception {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        Method method = DemoController.class.getMethod("queryContract", String.class);
        RequestMappingInfo info = RequestMappingInfo.paths("/contracts").methods(org.springframework.web.bind.annotation.RequestMethod.GET).build();
        mapping.registerMapping(info, new DemoController(), method);

        SdkDescriptionSourceSettingsHolder holder = new SdkDescriptionSourceSettingsHolder();
        RuntimeCapabilityMetadataResolver resolver = new RuntimeCapabilityMetadataResolver(holder);
        EafRegistryProperties props = new EafRegistryProperties();
        props.getProject().setBaseUrl("http://contract-service");
        props.getProject().setContextPath("/api");
        props.getCapability().setScanController(true);

        EafCapabilityScanner scanner = new EafCapabilityScanner(mapping, props, resolver);

        List<EafCapabilityDescriptor> descriptors = scanner.scan();

        assertEquals(1, descriptors.size());
        EafCapabilityDescriptor descriptor = descriptors.get(0);
        assertEquals("contract.query", descriptor.name());
        assertEquals("查询合同", descriptor.title());
        assertEquals("按合同编号查询合同", descriptor.description());
        assertEquals("READ_ONLY", descriptor.sideEffect());
        assertTrue(Boolean.TRUE.equals(descriptor.metadata().get("declared")));
        assertEquals("AiTool", descriptor.metadata().get("source"));
        assertEquals(List.of("contract.reader"), descriptor.metadata().get("requiredRoles"));
    }

    @RestController
    static class DemoController {
        @AiTool(
                name = "contract.query",
                title = "查询合同",
                description = "按合同编号查询合同",
                domain = "contract",
                module = "contract-core",
                requiredRoles = {"contract.reader"},
                sideEffect = SideEffectLevel.READ_ONLY)
        @GetMapping("/contracts")
        public String queryContract(@RequestParam("contractNo") String contractNo) {
            return contractNo;
        }
    }
}
