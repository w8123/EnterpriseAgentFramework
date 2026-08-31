package com.enterprise.ai.control.a2a.api.protocol;

import com.enterprise.ai.control.a2a.application.task.A2aTaskApplicationService;
import com.enterprise.ai.control.a2a.application.task.A2aTaskCompletionWaiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class A2aHttpJsonContentNegotiationTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        A2aHttpJsonController controller = new A2aHttpJsonController(
                mock(A2aProtocolRequestGuard.class),
                mock(A2aHttpJsonMapper.class),
                mock(A2aTaskApplicationService.class),
                mock(A2aTaskCompletionWaiter.class));
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new A2aProtocolExceptionHandler())
                .build();
    }

    @Test
    void rejectsUnsupportedContentTypeWithA2aHttpSemantics() throws Exception {
        mvc.perform(post("/a2a/v1/message:send")
                        .header("A2A-Version", "1.0")
                        .contentType(MediaType.APPLICATION_XML)
                        .accept("application/a2a+json")
                        .content("<message/>"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("A2A-Version", "1.0"))
                .andExpect(content().contentTypeCompatibleWith("application/a2a+json"))
                .andExpect(jsonPath("$.error.code").value(400))
                .andExpect(jsonPath("$.error.status").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("CONTENT_TYPE_NOT_SUPPORTED"));
    }

    @Test
    void rejectsMalformedJsonWithoutFallingBackToPlatformSuccessEnvelope() throws Exception {
        mvc.perform(post("/a2a/v1/message:send")
                        .header("A2A-Version", "1.0")
                        .contentType("application/a2a+json")
                        .accept("application/a2a+json")
                        .content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/a2a+json"))
                .andExpect(content().string(containsString("INVALID_ARGUMENT")))
                .andExpect(jsonPath("$.error.code").value(400));
    }

    @Test
    void usesOrdinaryJsonForGenericHttpClients() throws Exception {
        mvc.perform(post("/a2a/v1/message:send")
                        .header("A2A-Version", "1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.ALL)
                        .content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value(400));
    }
}
