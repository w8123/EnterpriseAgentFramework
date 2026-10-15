package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiParameter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReachSpringMvcImplicitBindingTest {
    @Test
    void implicitStringUsesActualSpringOptionalQueryBinding() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TokenController()).build();
        mvc.perform(get("/token").param("code", "code-1")).andExpect(status().isOk())
                .andExpect(content().string("code-1"));
        mvc.perform(get("/token")).andExpect(status().isOk()).andExpect(content().string("missing"));
        ReachHttpApiDescriptor operation = scan(TokenController.class, "/token");
        assertEquals(1, operation.getParameters().size());
        assertQuery(operation, "code", "string", false);
        assertNull(operation.getRequestBody());
    }

    @Test
    void implicitDtoUsesWritableBeanNamesIncludingInheritedPropertiesInsteadOfJsonFieldNames() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PageController()).build();
        mvc.perform(get("/page").param("pageIndex", "3").param("pageSize", "12").param("sendTo", "person"))
                .andExpect(status().isOk()).andExpect(content().string("3:12:person"));
        ReachHttpApiDescriptor operation = scan(PageController.class, "/page");
        assertEquals(3, operation.getParameters().size());
        assertQuery(operation, "pageIndex", "integer", false);
        assertQuery(operation, "pageSize", "integer", false);
        assertQuery(operation, "sendTo", "string", false);
        assertFalse(operation.getParameters().stream().anyMatch(p -> "json_receiver".equals(p.getName())));
        assertNull(operation.getRequestBody());
    }

    @Test
    void explicitModelAttributeUsesDottedNestedWritablePropertiesWithoutInventingDtoBody() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NestedController()).build();
        mvc.perform(get("/nested").param("address.city", "Qingdao"))
                .andExpect(status().isOk()).andExpect(content().string("Qingdao"));
        ReachHttpApiDescriptor operation = scan(NestedController.class, "/nested");
        assertQuery(operation, "address.city", "string", false);
        assertNull(operation.getRequestBody());
    }

    @Test
    void ignoresFrameworkInjectedRequestResponseAndKeepsUserInput() {
        ReachHttpApiDescriptor operation = scan(InjectedController.class, "/injected");
        assertEquals(1, operation.getParameters().size());
        assertQuery(operation, "code", "string", false);
    }

    @Test
    void implicitOptionalSimpleValueUnwrapsTheQuerySchema() {
        ReachHttpApiDescriptor operation = scan(OptionalController.class, "/optional");
        assertQuery(operation, "page", "integer", false);
    }

    @Test
    void uploadKeepsBinaryMultipartBodyAndOptionalSimpleQueryFields() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UploadController()).build();
        mvc.perform(multipart("/upload").file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1}))
                        .param("fileType", "document").param("fileName", "a.txt"))
                .andExpect(status().isOk()).andExpect(content().string("1:document:a.txt"));
        ReachHttpApiDescriptor operation = scan(UploadController.class, "/upload");
        assertEquals(2, operation.getParameters().size());
        assertQuery(operation, "fileType", "string", false);
        assertQuery(operation, "fileName", "string", false);
        assertNotNull(operation.getRequestBody());
        assertEquals(List.of("multipart/form-data"), operation.getRequestBody().getContentTypes());
        Map<?, ?> schema = operation.getRequestBody().getSchema();
        Map<?, ?> file = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("file");
        assertEquals("string", file.get("type"));
        assertEquals("binary", file.get("format"));
        assertEquals(List.of("file"), schema.get("required"));
        assertTrue(operation.getRequestBody().isRequired());
    }

    @Test
    void recursiveModelBindingIsExplicitlyBlockedRatherThanSilentlyDroppedOrMisrepresented() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReachSpringMvcEndpointScanner.scanClass(RecursiveController.class));
        assertTrue(failure.getMessage().contains("ModelAttribute"));
        assertTrue(failure.getMessage().contains("recursive"));
    }

    private static ReachHttpApiDescriptor scan(Class<?> type, String path) {
        List<ReachHttpApiDescriptor> operations = ReachSpringMvcEndpointScanner.scanClass(type);
        return operations.stream().filter(item -> path.equals(item.getEndpointPath())).findFirst().orElseThrow();
    }

    private static void assertQuery(ReachHttpApiDescriptor operation, String name, String type, boolean required) {
        ReachHttpApiParameter parameter = operation.getParameters().stream()
                .filter(item -> name.equals(item.getName())).findFirst().orElseThrow();
        assertEquals("QUERY", parameter.getLocation());
        assertEquals(type, parameter.getSchema().get("type"));
        assertEquals(required, parameter.isRequired());
    }

    @RestController
    static class TokenController {
        @GetMapping("/token") public String getToken(String code) { return code == null ? "missing" : code; }
    }
    public static class PageBase {
        private Integer pageIndex;
        public Integer getPageIndex() { return pageIndex; }
        public void setPageIndex(Integer value) { pageIndex = value; }
    }
    public static class PageDTO extends PageBase {
        private Integer pageSize;
        @com.fasterxml.jackson.annotation.JsonProperty("json_receiver") private String SendTo;
        private String unbindable;
        public Integer getPageSize() { return pageSize; }
        public void setPageSize(Integer value) { pageSize = value; }
        public String getSendTo() { return SendTo; }
        public void setSendTo(String value) { SendTo = value; }
        public String getUnbindable() { return unbindable; }
    }
    @RestController
    static class PageController {
        @GetMapping("/page") public String getPageInfo(PageDTO dto) {
            return dto.getPageIndex() + ":" + dto.getPageSize() + ":" + dto.getSendTo();
        }
    }
    public static class NestedDTO {
        private Address address;
        public Address getAddress() { return address; }
        public void setAddress(Address value) { address = value; }
    }
    public static class Address {
        private String city;
        public String getCity() { return city; }
        public void setCity(String value) { city = value; }
    }
    @RestController
    static class NestedController {
        @GetMapping("/nested") public String nested(@ModelAttribute NestedDTO dto) { return dto.getAddress().getCity(); }
    }
    @RestController
    static class InjectedController {
        @GetMapping("/injected") public String injected(HttpServletRequest request, HttpServletResponse response, String code) { return code; }
    }
    @RestController
    static class OptionalController {
        @GetMapping("/optional") public String optional(Optional<Integer> page) { return page.toString(); }
    }
    @RestController
    static class UploadController {
        @PostMapping("/upload") public String upload(@RequestParam("file") MultipartFile file, String fileType, String fileName) {
            return file.getSize() + ":" + fileType + ":" + fileName;
        }
    }
    public static class RecursiveDTO {
        private RecursiveDTO recursive;
        public RecursiveDTO getRecursive() { return recursive; }
        public void setRecursive(RecursiveDTO value) { recursive = value; }
    }
    @RestController
    static class RecursiveController {
        @GetMapping("/recursive") public String recursive(RecursiveDTO dto) { return ""; }
    }
}
