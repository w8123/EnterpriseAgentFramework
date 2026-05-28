package com.enterprise.ai.spring.registry;

import com.enterprise.ai.skill.AiCapability;
import com.enterprise.ai.skill.AiParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeCapabilityMetadataResolverTest {

    @Test
    void resolvesMethodNameWhenNoAnnotations() throws Exception {
        SdkDescriptionSourceSettingsHolder holder = new SdkDescriptionSourceSettingsHolder();
        RuntimeCapabilityMetadataResolver resolver = new RuntimeCapabilityMetadataResolver(holder);
        Method m = SampleApi.class.getMethod("plain");
        assertEquals("plain", resolver.resolveMethodDescription(m, (com.enterprise.ai.skill.AiCapability) null));
        assertEquals("", resolver.resolveMethodTitle(m, (com.enterprise.ai.skill.AiCapability) null));
    }

    @Test
    void aiCapabilityDescriptionOverridesChain() throws Exception {
        SdkDescriptionSourceSettingsHolder holder = new SdkDescriptionSourceSettingsHolder();
        RuntimeCapabilityMetadataResolver resolver = new RuntimeCapabilityMetadataResolver(holder);
        Method m = SampleApi.class.getMethod("annotated");
        AiCapability cap = m.getAnnotation(AiCapability.class);
        assertEquals("from-anno", resolver.resolveMethodDescription(m, cap));
        assertEquals("T", resolver.resolveMethodTitle(m, cap));
    }

    @Test
    void resolveMemberDescriptionUsesAiParamOnField() throws Exception {
        SdkDescriptionSourceSettingsHolder holder = new SdkDescriptionSourceSettingsHolder();
        RuntimeCapabilityMetadataResolver resolver = new RuntimeCapabilityMetadataResolver(holder);
        Field f = FieldHolder.class.getDeclaredField("code");
        assertEquals("编码说明", resolver.resolveMemberDescription(f, "code"));
    }

    abstract static class SampleApi {
        public void plain() {
        }

        @AiCapability(name = "x", title = "T", description = "from-anno")
        public void annotated() {
        }
    }

    static class FieldHolder {
        @AiParam(description = "编码说明")
        private String code;
    }
}
