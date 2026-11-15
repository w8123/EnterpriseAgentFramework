package com.enterprise.ai.reach.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReachAiActivityTest {

    private final CapturingTransport transport = new CapturingTransport();

    private WebApplicationContextRunner runner(String... extra) {
        List<String> properties = new ArrayList<String>(java.util.Arrays.asList(
                "reachai.registry.enabled=false",
                "reachai.registry.url=https://reachai.example.com",
                "reachai.registry.app-key=demo-key",
                "reachai.registry.app-secret=demo-secret",
                "reachai.project.code=Demo-ERP",
                "reachai.capability.scan-beans=false",
                "reachai.activity.flush-interval-ms=3600000"));
        properties.addAll(java.util.Arrays.asList(extra));
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ReachAiRegistryAutoConfiguration.class,
                        WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class))
                .withBean(ReachAiRegistryTransport.class, () -> transport)
                .withBean(CustomerController.class)
                .withBean(ErrorAdvice.class)
                .withBean(ReachAiEmbedPrincipalResolver.class, () -> agentId ->
                        new ReachAiEmbedPrincipal("7", null, "芋艿", Collections.<String>emptyList(),
                                Collections.<String, Object>singletonMap("tenantId", "1")))
                .withPropertyValues(properties.toArray(new String[0]));
    }

    @Test
    void staysOffUnlessEnabled() {
        runner().run(context -> assertFalse(context.containsBean("reachAiActivityRecorder")));
    }

    @Test
    void recordsOperationsSearchesAndFailuresWithoutTouchingResponses() {
        runner("reachai.activity.enabled=true").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
            mvc.perform(get("/erp/customer/page").param("name", "张三 13812345678")
                            .header("Referer", "https://erp.example.com/erp/customer?tab=1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
            mvc.perform(post("/erp/customer/create").header("Referer", "https://erp.example.com/erp/customer"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("客户名称不能为空"));
            mvc.perform(post("/erp/customer/42/delete").header("Referer", "https://erp.example.com/erp/customer"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
            // no Referer/Origin: a script or a server-side call, not an employee in a browser
            mvc.perform(get("/erp/customer/page")).andExpect(status().isOk());

            ReachAiActivityRecorder recorder = context.getBean(ReachAiActivityRecorder.class);
            assertEquals(3, recorder.pending());
            recorder.flush();
            assertEquals(1, transport.calls.size());
            assertEquals("https://reachai.example.com/api/activity-ingress/projects/demo-erp/events", transport.urls.get(0));
            assertTrue(transport.headers.get(0).keySet().stream().anyMatch(h -> h.toLowerCase().contains("signature")));
            JsonNode events = new ObjectMapper().readTree((byte[]) transport.calls.get(0)).path("events");
            assertEquals(3, events.size());

            JsonNode search = events.get(0);
            assertEquals("SEARCH", search.path("type").asText());
            assertEquals("GET /erp/customer/page", search.path("operation").asText());
            assertEquals("客户分页", search.path("operationName").asText());
            assertEquals("张三 138****5678", search.path("keyword").asText());
            assertEquals("/erp/customer", search.path("page").asText());
            assertEquals("SUCCESS", search.path("outcome").asText());
            assertEquals("7", search.path("userId").asText());
            assertEquals("芋艿", search.path("userName").asText());
            assertEquals("1", search.path("tenantId").asText());

            JsonNode business = events.get(1);
            assertEquals("POST /erp/customer/create", business.path("operation").asText());
            assertEquals("FAILED", business.path("outcome").asText());
            assertEquals("客户名称不能为空", business.path("error").asText());

            JsonNode handled = events.get(2);
            assertEquals("POST /erp/customer/{id}/delete", handled.path("operation").asText());
            assertEquals("FAILED", handled.path("outcome").asText());
            assertEquals("客户还有未完成的订单", handled.path("error").asText());
        });
    }

    @Test
    void pausesWhenReachAiSwitchesCollectionOff() {
        runner("reachai.activity.enabled=true").run(context -> {
            transport.answer = "{\"accepted\":0,\"dropped\":0,\"enabled\":false,\"captureSearch\":true}";
            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
            mvc.perform(get("/erp/customer/page").header("Origin", "https://erp.example.com"));
            ReachAiActivityRecorder recorder = context.getBean(ReachAiActivityRecorder.class);
            recorder.flush();
            mvc.perform(get("/erp/customer/page").header("Origin", "https://erp.example.com"));
            assertEquals(0, recorder.pending());
        });
    }

    @Test
    void businessFailureDetectionReadsCommonResultShapes() {
        List<Integer> ok = java.util.Arrays.asList(0, 200);
        assertEquals(null, ReachAiActivityResponseAdvice.businessFailure(new Result(0, "ok"), ok));
        assertEquals("库存不足", ReachAiActivityResponseAdvice.businessFailure(new Result(1002, "库存不足"), ok));
        assertEquals("业务返回码 5", ReachAiActivityResponseAdvice.businessFailure(Collections.singletonMap("code", "5"), ok));
        assertEquals(null, ReachAiActivityResponseAdvice.businessFailure("plain text", ok));
    }

    public static class Result {
        private final int code;
        private final String msg;

        public Result(int code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        public int getCode() { return code; }
        public String getMsg() { return msg; }
    }

    @RestController
    public static class CustomerController {
        @io.swagger.annotations.ApiOperation("客户分页")
        @GetMapping("/erp/customer/page")
        public Result page(@RequestParam(required = false) String name) {
            return new Result(0, "ok");
        }

        @PostMapping("/erp/customer/create")
        public Result create() {
            return new Result(1002, "客户名称不能为空");
        }

        @PostMapping("/erp/customer/{id}/delete")
        public Result delete(@PathVariable Long id) {
            throw new IllegalStateException("客户还有未完成的订单");
        }
    }

    @RestControllerAdvice
    public static class ErrorAdvice {
        @ExceptionHandler(IllegalStateException.class)
        public Map<String, Object> handle(IllegalStateException failure) {
            Map<String, Object> body = new java.util.LinkedHashMap<String, Object>();
            body.put("code", 500);
            body.put("msg", failure.getMessage());
            return body;
        }
    }

    static class CapturingTransport implements ReachAiRegistryTransport {
        final List<Object> calls = new ArrayList<Object>();
        final List<String> urls = new ArrayList<String>();
        final List<Map<String, String>> headers = new ArrayList<Map<String, String>>();
        String answer = "{\"accepted\":1,\"enabled\":true,\"captureSearch\":true}";

        @Override
        public String exchange(String method, String url, Map<String, String> requestHeaders, Object body) throws IOException {
            urls.add(url);
            headers.add(requestHeaders);
            calls.add(body);
            return answer;
        }
    }
}
