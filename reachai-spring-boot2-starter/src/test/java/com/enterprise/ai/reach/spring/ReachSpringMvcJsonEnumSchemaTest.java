package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReachSpringMvcJsonEnumSchemaTest {
    @Test
    void jsonBodyBooleanCodeMatchesTheRealContextualBusinessDeserializerShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReachHttpApiDescriptor api = operation("/enum-body", mapper);
        assertEquals("boolean", property(api.getRequestBody().getSchema(), "enabled").get("type"));
        assertTrue(api.getConsumes().isEmpty());
        MockMvc mvc = mvc(mapper);
        for (boolean enabled : new boolean[]{true, false}) {
            MvcResult result = mvc.perform(post("/enum-body").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":" + enabled + "}"))
                    .andExpect(status().isOk()).andReturn();
            assertEquals(enabled, mapper.readTree(result.getResponse().getContentAsByteArray()).path("enabled").asBoolean());
        }
    }

    @Test
    void jsonResponseBooleanCodeMatchesActualJacksonWireValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("boolean", property(operation("/enum-response", mapper).getResponses().get(0).getSchema(), "enabled").get("type"));
        MvcResult result = mvc(mapper).perform(get("/enum-response")).andExpect(status().isOk()).andReturn();
        assertTrue(mapper.readTree(result.getResponse().getContentAsByteArray()).path("enabled").isBoolean());
    }

    @Test
    void queryAndPathEnumsKeepSpringNameStringsEvenWhenJsonValueIsBoolean() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("string", operation("/enum-query", mapper).getParameters().get(0).getSchema().get("type"));
        assertEquals("string", operation("/enum-path/{enabled}", mapper).getParameters().get(0).getSchema().get("type"));
        MockMvc mvc = mvc(mapper);
        mvc.perform(get("/enum-query").param("enabled", "Enable")).andExpect(status().isOk());
        mvc.perform(get("/enum-query").param("enabled", "true")).andExpect(status().isBadRequest());
        mvc.perform(get("/enum-path/Enable")).andExpect(status().isOk());
        mvc.perform(get("/enum-path/true")).andExpect(status().isBadRequest());
    }

    @Test
    void defaultJsonEnumRetainsNameStringRatherThanInheritingBooleanCodes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReachHttpApiDescriptor api = operation("/plain-enum", mapper);
        assertEquals("string", property(api.getRequestBody().getSchema(), "state").get("type"));
        assertEquals("string", property(api.getResponses().get(0).getSchema(), "state").get("type"));
        MvcResult result = mvc(mapper).perform(post("/plain-enum").contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"OPEN\"}")).andExpect(status().isOk()).andReturn();
        assertEquals("OPEN", mapper.readTree(result.getResponse().getContentAsByteArray()).path("state").asText());
    }

    @Test
    void actualMapperAnnotationConfigurationWinsOverReflectiveJsonValueGuessing() {
        ObjectMapper mapper = new ObjectMapper().disable(MapperFeature.USE_ANNOTATIONS);
        assertEquals("string", property(operation("/enum-response", mapper).getResponses().get(0).getSchema(), "enabled").get("type"));
    }

    @Test
    void mixedJsonValueKindsStayUnknownInsteadOfChoosingOneConstant() {
        assertEquals(Map.of(), property(operation("/mixed-enum", new ObjectMapper()).getResponses().get(0).getSchema(), "state"));
    }

    @Test
    void customObjectEncodingStaysUnknownInsteadOfAssumingEnumNames() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(BooleanCode.class, new JsonSerializer<BooleanCode>() {
            @Override public void serialize(BooleanCode value, JsonGenerator output, SerializerProvider provider) throws IOException {
                output.writeStartObject(); output.writeBooleanField("custom", value.code); output.writeEndObject();
            }
        });
        mapper.registerModule(module);
        assertEquals(Map.of(), property(operation("/enum-response", mapper).getResponses().get(0).getSchema(), "enabled"));
    }

    @Test
    void numericJsonValueRetainsItsNumericWireType() {
        assertEquals("integer", property(operation("/numeric-enum", new ObjectMapper()).getResponses().get(0).getSchema(), "category").get("type"));
    }

    @Test
    void fieldSpecificCustomCodecIsUnknownRatherThanUsingTheEnumDefaultMapper() {
        assertEquals(Map.of(), property(operation("/field-custom-enum", new ObjectMapper()).getResponses().get(0).getSchema(), "enabled"));
    }

    private static ReachHttpApiDescriptor operation(String route, ObjectMapper mapper) {
        return ReachSpringMvcEndpointScanner.scanClass(EnumController.class, Arrays.asList(
                new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper))).stream()
                .filter(api -> route.equals(api.getEndpointPath())).findFirst().orElseThrow();
    }

    private static MockMvc mvc(ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new EnumController()).setMessageConverters(
                new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private static Map<?, ?> property(Map<?, ?> schema, String name) { return (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get(name); }

    enum BooleanCode {
        Disable(false), Enable(true);
        @JsonValue final Boolean code;
        BooleanCode(Boolean value) { code = value; }
    }
    enum PlainState { OPEN, CLOSED }
    enum Category {
        Score(0), Bonus(1);
        @JsonValue final Integer code;
        Category(Integer value) { code = value; }
    }
    enum MixedCode {
        FIRST(false), SECOND("second");
        @JsonValue final Object code;
        MixedCode(Object value) { code = value; }
    }
    public static class FilterPage {
        @JsonDeserialize(using = BusinessEnumDeserializer.class)
        public BooleanCode enabled;
    }
    public static class PlainPage { public PlainState state; }
    public static class MixedPage { public MixedCode state = MixedCode.FIRST; }
    public static class NumericPage { public Category category = Category.Score; }
    public static class FieldCustomPage {
        @JsonSerialize(using = StructuredCodeSerializer.class)
        public BooleanCode enabled = BooleanCode.Enable;
    }
    public static class StructuredCodeSerializer extends JsonSerializer<BooleanCode> {
        @Override public void serialize(BooleanCode value, JsonGenerator output, SerializerProvider provider) throws IOException {
            output.writeStartObject(); output.writeBooleanField("custom", value.code); output.writeEndObject();
        }
    }

    /** Mirrors QMSSMP BaseEnumDeserializer's parser-current-DTO/field/code lookup. */
    public static class BusinessEnumDeserializer extends JsonDeserializer<BooleanCode> {
        @Override public BooleanCode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            try {
                Object owner = parser.getParsingContext().getCurrentValue();
                Field field = owner.getClass().getDeclaredField(parser.getParsingContext().getCurrentName());
                for (Object constant : field.getType().getEnumConstants()) {
                    BooleanCode candidate = (BooleanCode) constant;
                    if (candidate.code.toString().equals(parser.getText())) return candidate;
                }
                throw new IOException("Unknown enum code");
            } catch (ReflectiveOperationException failure) { throw new IOException(failure); }
        }
    }
    @RestController
    static class EnumController {
        @PostMapping("/enum-body") public FilterPage filter(@RequestBody FilterPage page) { return page; }
        @GetMapping("/enum-response") public FilterPage response() { FilterPage page = new FilterPage(); page.enabled = BooleanCode.Enable; return page; }
        @GetMapping("/enum-query") public FilterPage query(@RequestParam("enabled") BooleanCode enabled) { FilterPage page = new FilterPage(); page.enabled = enabled; return page; }
        @GetMapping("/enum-path/{enabled}") public FilterPage path(@PathVariable("enabled") BooleanCode enabled) { FilterPage page = new FilterPage(); page.enabled = enabled; return page; }
        @PostMapping("/plain-enum") public PlainPage plain(@RequestBody PlainPage page) { return page; }
        @GetMapping("/mixed-enum") public MixedPage mixed() { return new MixedPage(); }
        @GetMapping("/numeric-enum") public NumericPage numeric() { return new NumericPage(); }
        @GetMapping("/field-custom-enum") public FieldCustomPage fieldCustom() { return new FieldCustomPage(); }
    }
}
