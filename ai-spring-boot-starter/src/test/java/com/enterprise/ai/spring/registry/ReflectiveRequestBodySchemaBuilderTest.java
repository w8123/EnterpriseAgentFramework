package com.enterprise.ai.spring.registry;

import com.enterprise.ai.skill.AiParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveRequestBodySchemaBuilderTest {

    @Test
    void expandsNestedDtoAndJsonPropertyAlias() {
        SdkDescriptionSourceSettingsHolder holder = new SdkDescriptionSourceSettingsHolder();
        RuntimeCapabilityMetadataResolver resolver = new RuntimeCapabilityMetadataResolver(holder);
        ReflectiveRequestBodySchemaBuilder builder = new ReflectiveRequestBodySchemaBuilder(resolver);
        List<EafCapabilityParameter> rows = builder.expand(RootDto.class);
        assertEquals(2, rows.size());
        EafCapabilityParameter renamed = rows.stream().filter(r -> "renamedTitle".equals(r.name())).findFirst().orElseThrow();
        assertEquals("string", renamed.type());
        EafCapabilityParameter nested = rows.stream().filter(r -> "addr".equals(r.name())).findFirst().orElseThrow();
        assertNotNull(nested.children());
        assertEquals(1, nested.children().size());
        assertEquals("street", nested.children().get(0).name());
        assertEquals("BODY", nested.children().get(0).location());
    }

    @Test
    void recordComponentsBecomeRows() {
        SdkDescriptionSourceSettingsHolder holder = new SdkDescriptionSourceSettingsHolder();
        RuntimeCapabilityMetadataResolver resolver = new RuntimeCapabilityMetadataResolver(holder);
        ReflectiveRequestBodySchemaBuilder builder = new ReflectiveRequestBodySchemaBuilder(resolver);
        List<EafCapabilityParameter> rows = builder.expand(PointRecord.class);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "x".equals(r.name()) && "integer".equals(r.type())));
        assertTrue(rows.stream().anyMatch(r -> "y".equals(r.name()) && "integer".equals(r.type())));
    }

    @Test
    void aiParamDescriptionOnFieldWins() {
        SdkDescriptionSourceSettingsHolder holder = new SdkDescriptionSourceSettingsHolder();
        RuntimeCapabilityMetadataResolver resolver = new RuntimeCapabilityMetadataResolver(holder);
        ReflectiveRequestBodySchemaBuilder builder = new ReflectiveRequestBodySchemaBuilder(resolver);
        List<EafCapabilityParameter> rows = builder.expand(AnnotatedLeaf.class);
        EafCapabilityParameter id = rows.stream().filter(r -> "id".equals(r.name())).findFirst().orElseThrow();
        assertEquals("业务主键", id.description());
        assertTrue(id.required());
        assertNotNull(id.metadata());
        assertEquals("ex1", id.metadata().get("example"));
    }

    static class RootDto {
        @JsonProperty("renamedTitle")
        String title;
        InnerAddr addr;
    }

    static class InnerAddr {
        @AiParam(description = "街道", required = true)
        String street;
    }

    record PointRecord(int x, int y) {
    }

    static class AnnotatedLeaf {
        @AiParam(description = "业务主键", required = true, example = "ex1")
        String id;
    }
}
