package com.enterprise.ai.spring.registry;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EafCapabilityScannerParametersTest {

    @Test
    void pathQueryAndBodyJsonLocations() throws Exception {
        SdkDescriptionSourceSettingsHolder holder = new SdkDescriptionSourceSettingsHolder();
        RuntimeCapabilityMetadataResolver resolver = new RuntimeCapabilityMetadataResolver(holder);
        EafRegistryProperties props = new EafRegistryProperties();
        props.getCapability().setScanController(true);
        EafCapabilityScanner scanner = new EafCapabilityScanner(null, props, resolver);

        Method m = DemoController.class.getMethod("submit", Long.class, boolean.class, BodyForScan.class);
        HandlerMethod hm = new HandlerMethod(new DemoController(), m);
        Method priv = EafCapabilityScanner.class.getDeclaredMethod("parameters", HandlerMethod.class, Method.class);
        priv.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<EafCapabilityParameter> ps = (List<EafCapabilityParameter>) priv.invoke(scanner, hm, m);

        assertEquals(3, ps.size());
        EafCapabilityParameter path = ps.stream().filter(p -> "orderId".equals(p.name())).findFirst().orElseThrow();
        assertEquals("PATH", path.location());
        EafCapabilityParameter q = ps.stream().filter(p -> "full".equals(p.name())).findFirst().orElseThrow();
        assertEquals("QUERY", q.location());
        assertFalse(q.required());

        EafCapabilityParameter body = ps.stream().filter(p -> "body_json".equals(p.name())).findFirst().orElseThrow();
        assertEquals("BODY", body.location());
        assertEquals("json", body.type());
        assertFalse(body.children().isEmpty());
        assertTrue(body.children().stream().anyMatch(c -> "titleField".equals(c.name())));
    }

    static class BodyForScan {
        String titleField;
    }

    @RestController
    static class DemoController {
        @PostMapping("/orders/{orderId}")
        public void submit(@PathVariable("orderId") Long orderId,
                           @RequestParam(value = "full", defaultValue = "false") boolean full,
                           @RequestBody BodyForScan body) {
        }
    }
}
