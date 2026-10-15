package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReachSpringMvcMediaEvidenceTest {
    @Test
    void actualConfiguredJsonConverterProvesJsonResponseWithoutInventingMappingCondition() {
        try (GenericApplicationContext context = context(new MappingJackson2HttpMessageConverter())) {
            ReachHttpApiDescriptor api = operation(context, "/object");
            assertTrue(api.getProduces().isEmpty());
            assertEquals(List.of("application/json"), api.getResponses().get(0).getContentTypes());
            assertEquals("object", api.getResponses().get(0).getSchema().get("type"));
        }
    }

    @Test
    void missingJsonConverterKeepsResponseUnknownInsteadOfAssumingRestControllerMeansJson() {
        try (GenericApplicationContext context = context(new ByteArrayHttpMessageConverter(), new StringHttpMessageConverter())) {
            assertTrue(operation(context, "/object").getResponses().get(0).getContentTypes().isEmpty());
        }
    }

    @Test
    void scalarAndBinaryResponsesAreNotForcedToJsonObjects() {
        try (GenericApplicationContext context = context(new MappingJackson2HttpMessageConverter(),
                new StringHttpMessageConverter(), new ByteArrayHttpMessageConverter())) {
            assertTrue(operation(context, "/text").getResponses().get(0).getContentTypes().isEmpty());
            assertTrue(operation(context, "/bytes").getResponses().get(0).getContentTypes().isEmpty());
            assertEquals("string", operation(context, "/text").getResponses().get(0).getSchema().get("type"));
        }
    }

    @Test
    void explicitNegotiationRemainsTheSourceOfTruth() {
        try (GenericApplicationContext context = context(new MappingJackson2HttpMessageConverter())) {
            ReachHttpApiDescriptor api = operation(context, "/xml");
            assertEquals(List.of("application/xml"), api.getProduces());
            assertEquals(List.of("application/xml"), api.getResponses().get(0).getContentTypes());
        }
    }

    @Test
    void actualJsonReaderProvesRequestBodyMediaWithoutAddingAConsumesMappingRestriction() {
        try (GenericApplicationContext context = context(new MappingJackson2HttpMessageConverter())) {
            ReachHttpApiDescriptor api = operation(context, "/body");
            assertTrue(api.getConsumes().isEmpty());
            assertEquals(List.of("application/json"), api.getRequestBody().getContentTypes());
        }
    }

    @Test
    void staticScannerWithoutMvcContextKeepsUnknownMedia() {
        ReachHttpApiDescriptor api = ReachSpringMvcEndpointScanner.scanClass(MediaController.class).stream()
                .filter(item -> "/object".equals(item.getEndpointPath())).findFirst().orElseThrow();
        assertTrue(api.getResponses().get(0).getContentTypes().isEmpty());
    }

    @Test
    void genericListReaderProvesJsonRequestWithoutAddingAConsumesRestriction() throws Exception {
        GenericListJsonReader reader = new GenericListJsonReader();
        try (GenericApplicationContext context = context(reader)) {
            ReachHttpApiDescriptor api = operation(context, "/list-body");
            assertTrue(api.getConsumes().isEmpty());
            assertEquals(List.of("application/json"), api.getRequestBody().getContentTypes());
            assertEquals("array", api.getRequestBody().getSchema().get("type"));
            assertEquals(MediaController.class.getDeclaredMethod("listBody", List.class).getGenericParameterTypes()[0], reader.acceptedType);
        }
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaController()).setMessageConverters(reader).build();
        MvcResult result = mvc.perform(post("/list-body").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"data\":\"typed-element\"}]"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("typed-element", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsByteArray()).path("data").asText());
    }

    @Test
    void arrayAndHttpEntityListRequestsUseActualJsonReaderEvidence() {
        try (GenericApplicationContext context = context(new MappingJackson2HttpMessageConverter())) {
            assertEquals(List.of("application/json"), operation(context, "/array-body").getRequestBody().getContentTypes());
            assertEquals(List.of("application/json"), operation(context, "/entity-list-body").getRequestBody().getContentTypes());
            assertTrue(operation(context, "/array-body").getConsumes().isEmpty());
        }
    }

    @Test
    void stringConverterFirstSelectsPlainTextAndMockMvcReceivesRawUtf8WithoutJsonQuotes() throws Exception {
        StringHttpMessageConverter strings = new StringHttpMessageConverter();
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter();
        try (GenericApplicationContext context = context(strings, json)) {
            ReachHttpApiDescriptor api = operation(context, "/string-body");
            assertEquals(List.of("text/plain"), api.getRequestBody().getContentTypes());
            assertTrue(api.getConsumes().isEmpty());
        }
        String raw = "原始UTF8文本\n\"不加JSON外层引号\"";
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaController()).setMessageConverters(strings, json).build();
        MvcResult result = mvc.perform(post("/string-body").contentType("text/plain;charset=UTF-8")
                .content(raw.getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk()).andReturn();
        assertEquals(raw, new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsByteArray()).path("data").asText());
    }

    @Test
    void jacksonReaderFirstKeepsJsonStringEncodingInsteadOfPretendingItIsPlainText() throws Exception {
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter();
        StringHttpMessageConverter strings = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        try (GenericApplicationContext context = context(json, strings)) {
            assertEquals(List.of("application/json"), operation(context, "/string-body").getRequestBody().getContentTypes());
        }
        String raw = "JSON字符串";
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaController()).setMessageConverters(json, strings).build();
        MvcResult result = mvc.perform(post("/string-body").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(raw))).andExpect(status().isOk()).andReturn();
        assertEquals(raw, mapper.readTree(result.getResponse().getContentAsByteArray()).path("data").asText());
    }

    @Test
    void converterThatRejectsTheActualGenericElementKeepsListMediaUnknown() {
        try (GenericApplicationContext context = context(new GenericListJsonReader())) {
            assertTrue(operation(context, "/other-list-body").getRequestBody().getContentTypes().isEmpty());
        }
    }

    @Test
    void missingReaderKeepsStringAndListMediaUnknownAndDoesNotTreatBinaryAsJsonArray() {
        try (GenericApplicationContext context = context(new ByteArrayHttpMessageConverter())) {
            assertTrue(operation(context, "/string-body").getRequestBody().getContentTypes().isEmpty());
            assertTrue(operation(context, "/list-body").getRequestBody().getContentTypes().isEmpty());
            assertTrue(operation(context, "/binary-body").getRequestBody().getContentTypes().isEmpty());
        }
    }

    @Test
    void customCodecKeepsUnknownMediaInsteadOfAssumingStandardJsonOrPlainText() {
        StringHttpMessageConverter custom = new StringHttpMessageConverter();
        custom.setSupportedMediaTypes(List.of(MediaType.valueOf("application/x-enterprise-codec")));
        try (GenericApplicationContext context = context(custom)) {
            assertTrue(operation(context, "/string-body").getRequestBody().getContentTypes().isEmpty());
            assertTrue(operation(context, "/list-body").getRequestBody().getContentTypes().isEmpty());
        }
    }

    @Test
    void explicitRequestMediaIsPreservedInsteadOfReplacedByConverterPreference() {
        try (GenericApplicationContext context = context(new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter())) {
            ReachHttpApiDescriptor api = operation(context, "/xml-body");
            assertEquals(List.of("application/xml"), api.getConsumes());
            assertEquals(List.of("application/xml"), api.getRequestBody().getContentTypes());
        }
    }

    @Test
    void rootArrayResponseRequiresAndUsesActualGenericJsonWriterEvidence() throws Exception {
        GenericListJsonWriter writer = new GenericListJsonWriter();
        try (GenericApplicationContext context = context(writer)) {
            ReachHttpApiDescriptor api = operation(context, "/array-response");
            assertTrue(api.getProduces().isEmpty());
            assertEquals(List.of("application/json"), api.getResponses().get(0).getContentTypes());
            assertEquals("array", api.getResponses().get(0).getSchema().get("type"));
            assertEquals(MediaController.class.getDeclaredMethod("arrayResponse").getGenericReturnType(), writer.acceptedType);
        }
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaController()).setMessageConverters(writer).build();
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/array-response"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsByteArray()).isArray());
        try (GenericApplicationContext context = context(new StringHttpMessageConverter())) {
            assertTrue(operation(context, "/array-response").getResponses().get(0).getContentTypes().isEmpty());
        }
    }

    @Test
    void voidReturnDoesNotInventJsonPayloadOrAdviceFromAJacksonConverter() throws Exception {
        try (GenericApplicationContext context = context(new MappingJackson2HttpMessageConverter())) {
            ReachHttpApiDescriptor api = operation(context, "/void-response");
            assertTrue(api.getProduces().isEmpty());
            assertTrue(api.getResponses().get(0).getContentTypes().isEmpty());
            assertTrue(api.getResponses().get(0).getSchema().isEmpty());
        }
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaController()).setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/void-response"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, result.getResponse().getContentAsByteArray().length);
        assertNull(result.getResponse().getContentType());
    }

    static class GenericListJsonReader extends MappingJackson2HttpMessageConverter {
        Type acceptedType;
        @Override public boolean canRead(Class<?> clazz, MediaType mediaType) { return false; }
        @Override public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
            if (!(type instanceof ParameterizedType)) return false;
            ParameterizedType parameterized = (ParameterizedType) type;
            if (parameterized.getRawType() != List.class || parameterized.getActualTypeArguments()[0] != Envelope.class) return false;
            boolean accepted = super.canRead(type, contextClass, mediaType);
            if (accepted) acceptedType = type;
            return accepted;
        }
    }

    static class GenericListJsonWriter extends MappingJackson2HttpMessageConverter {
        Type acceptedType;
        @Override public boolean canWrite(Class<?> clazz, MediaType mediaType) { return false; }
        @Override public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
            if (!(type instanceof ParameterizedType)) return false;
            ParameterizedType parameterized = (ParameterizedType) type;
            if (parameterized.getRawType() != List.class || parameterized.getActualTypeArguments()[0] != Envelope.class) return false;
            boolean accepted = (mediaType == null || MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) && getObjectMapper().canSerialize(Envelope.class);
            if (accepted) acceptedType = type;
            return accepted;
        }
    }

    private static GenericApplicationContext context(HttpMessageConverter<?>... converters) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("mediaController", MediaController.class);
        context.registerBean("requestMappingHandlerAdapter", RequestMappingHandlerAdapter.class, () -> {
            RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
            adapter.setMessageConverters(Arrays.asList(converters));
            return adapter;
        });
        context.refresh();
        return context;
    }

    private static ReachHttpApiDescriptor operation(GenericApplicationContext context, String path) {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getCapability().setScanPackages(Collections.singletonList(MediaController.class.getName()));
        return new ReachCapabilityBeanScanner(context, properties).scanHttpApis().stream()
                .filter(item -> path.equals(item.getEndpointPath())).findFirst().orElseThrow();
    }

    public static class Envelope {
        public String data;
    }
    @RestController
    static class MediaController {
        @GetMapping("/object") public Envelope object() { return new Envelope(); }
        @GetMapping("/text") public String text() { return "text"; }
        @GetMapping("/bytes") public byte[] bytes() { return new byte[]{1}; }
        @GetMapping("/array-response") public List<Envelope> arrayResponse() { return List.of(new Envelope()); }
        @GetMapping("/void-response") public void voidResponse() { }
        @GetMapping(value = "/xml", produces = "application/xml") public Envelope xml() { return new Envelope(); }
        @PostMapping("/body") public Envelope body(@RequestBody Envelope envelope) { return envelope; }
        @PostMapping("/list-body") public Envelope listBody(@RequestBody List<Envelope> body) { return body.get(0); }
        @PostMapping("/other-list-body") public Envelope otherListBody(@RequestBody List<String> body) { return new Envelope(); }
        @PostMapping("/array-body") public Envelope arrayBody(@RequestBody Envelope[] body) { return body[0]; }
        @PostMapping("/entity-list-body") public Envelope entityListBody(HttpEntity<List<Envelope>> body) { return body.getBody().get(0); }
        @PostMapping("/string-body") public Envelope stringBody(@RequestBody String body) { Envelope result = new Envelope(); result.data = body; return result; }
        @PostMapping("/binary-body") public Envelope binaryBody(@RequestBody byte[] body) { return new Envelope(); }
        @PostMapping(value = "/xml-body", consumes = "application/xml") public Envelope xmlBody(@RequestBody String body) { return new Envelope(); }
    }
}
