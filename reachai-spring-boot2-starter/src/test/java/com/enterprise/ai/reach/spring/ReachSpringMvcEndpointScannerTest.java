package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiMappingCondition;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiParameter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachSpringMvcEndpointScannerTest {

    @Test
    void ignoresNullAndNonControllerTypes() {
        assertTrue(ReachSpringMvcEndpointScanner.scanClass(null).isEmpty());
        assertTrue(ReachSpringMvcEndpointScanner.scanClass(PlainBean.class).isEmpty());
    }

    @Test
    void expandsClassMethodPathAndHttpMethodCartesianProduct() {
        List<ReachHttpApiDescriptor> operations = ReachSpringMvcEndpointScanner.scanClass(CartesianController.class);

        assertEquals(8, operations.size());
        Set<String> identities = new HashSet<String>();
        for (ReachHttpApiDescriptor operation : operations) {
            identities.add(operation.getHttpMethod() + " " + operation.getEndpointPath());
        }
        assertEquals(Set.of("GET /v1/a", "POST /v1/a", "GET /v1/b", "POST /v1/b",
                "GET /v2/a", "POST /v2/a", "GET /v2/b", "POST /v2/b"), identities);
    }

    @Test
    void combinesClassAndMethodRequestMethodsUsingSpringConditionSemantics() {
        List<ReachHttpApiDescriptor> operations = ReachSpringMvcEndpointScanner.scanClass(MethodUnionController.class);

        assertEquals(Set.of("GET", "POST"), methods(operations));
        assertEquals(2, operations.size());
    }

    @Test
    void movesContentTypeAndAcceptHeaderExpressionsIntoMediaConditions() {
        ReachHttpApiDescriptor operation = ReachSpringMvcEndpointScanner.scanClass(ContentHeaderController.class).get(0);

        assertEquals(List.of("application/problem+json"), operation.getConsumes());
        assertEquals(List.of("application/problem+xml"), operation.getProduces());
        assertCondition(operation.getMappingConditions(), "HEADER", "X-Class", "PRESENT", null);
        assertCondition(operation.getMappingConditions(), "HEADER", "X-Method", "PRESENT", null);
        assertFalse(operation.getMappingConditions().stream().anyMatch(condition ->
                "HEADER".equals(condition.getKind())
                        && ("Content-Type".equalsIgnoreCase(condition.getName())
                        || "Accept".equalsIgnoreCase(condition.getName()))));
    }

    @Test
    void unrestrictedRequestMappingRepresentsAllSpringMethodsInsteadOfPretendingGet() {
        List<ReachHttpApiDescriptor> operations = ReachSpringMvcEndpointScanner.scanClass(UnrestrictedController.class);

        assertEquals(RequestMethod.values().length, operations.size());
        assertTrue(methods(operations).containsAll(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE")));
        assertNotEquals(1, operations.size());
    }

    @Test
    void preservesMappingConditionsAndMethodLevelContentNegotiationWithStableKeys() {
        List<ReachHttpApiDescriptor> first = ReachSpringMvcEndpointScanner.scanClass(ConditionalController.class);
        List<ReachHttpApiDescriptor> repeated = ReachSpringMvcEndpointScanner.scanClass(ConditionalController.class);

        assertEquals(1, first.size());
        ReachHttpApiDescriptor operation = first.get(0);
        assertEquals("POST", operation.getHttpMethod());
        assertEquals(List.of("application/json"), operation.getConsumes());
        assertEquals(List.of("application/problem+json"), operation.getProduces());
        assertEquals(4, operation.getMappingConditions().size());
        assertCondition(operation.getMappingConditions(), "PARAM", "tenant", "EQUALS", "north");
        assertCondition(operation.getMappingConditions(), "PARAM", "legacy", "ABSENT", null);
        assertCondition(operation.getMappingConditions(), "HEADER", "X-Mode", "PRESENT", null);
        assertCondition(operation.getMappingConditions(), "HEADER", "X-Version", "NOT_EQUALS", "v1");
        assertEquals(operation.getSourceKey(), repeated.get(0).getSourceKey());
        assertEquals("UNKNOWN", operation.getAuthenticationState());
        assertEquals("DEFAULT", operation.getResponses().get(0).getStatus());
    }

    @Test
    void mapsKnownParameterLocationsAndSafeShapesWithoutInventingBodies() {
        ReachHttpApiDescriptor operation = ReachSpringMvcEndpointScanner.scanClass(ParameterController.class).get(0);

        assertParameter(operation.getParameters(), "id", "PATH", true, "integer");
        assertParameter(operation.getParameters(), "q", "QUERY", false, "string");
        assertParameter(operation.getParameters(), "filter", "QUERY", false, "object");
        assertParameter(operation.getParameters(), "X-Trace", "HEADER", true, "string");
        assertParameter(operation.getParameters(), "session", "COOKIE", false, "string");
        assertTrue(operation.getRequestBody().isRequired());
        assertEquals("array", operation.getRequestBody().getSchema().get("type"));
        assertEquals("string", ((java.util.Map<?, ?>) operation.getRequestBody().getSchema().get("items")).get("type"));
        assertFalse(operation.getRequestBody().getSchema().containsKey("example"));
    }

    @Test
    void defaultValuesAndOptionalParametersAreNotRequiredAndUnwrapTheirSchema() {
        ReachHttpApiDescriptor operation = ReachSpringMvcEndpointScanner.scanClass(OptionalInputController.class).get(0);

        assertParameter(operation.getParameters(), "limit", "QUERY", false, "string");
        assertParameter(operation.getParameters(), "X-Tenant", "HEADER", false, "string");
        assertParameter(operation.getParameters(), "region", "COOKIE", false, "string");
        assertParameter(operation.getParameters(), "page", "QUERY", false, "integer");
        assertFalse(operation.getRequestBody().isRequired());
        assertEquals("array", operation.getRequestBody().getSchema().get("type"));
        assertEquals("string", ((Map<?, ?>) operation.getRequestBody().getSchema().get("items")).get("type"));
    }

    @Test
    void retainsDeclaredResponseStatusAndUnwrapsCommonResponseBodies() {
        List<ReachHttpApiDescriptor> operations = ReachSpringMvcEndpointScanner.scanClass(ResponseController.class);
        ReachHttpApiDescriptor created = operation(operations, "/responses/created");
        ReachHttpApiDescriptor classFallback = operation(operations, "/responses/class-fallback");
        ReachHttpApiDescriptor dynamic = ReachSpringMvcEndpointScanner.scanClass(DynamicResponseController.class).get(0);

        assertEquals("201", created.getResponses().get(0).getStatus());
        assertEquals("array", created.getResponses().get(0).getSchema().get("type"));
        assertEquals("string", ((Map<?, ?>) created.getResponses().get(0).getSchema().get("items")).get("type"));
        assertEquals("202", classFallback.getResponses().get(0).getStatus());
        assertEquals("object", classFallback.getResponses().get(0).getSchema().get("type"));
        assertEquals("DEFAULT", dynamic.getResponses().get(0).getStatus());
        assertEquals("array", dynamic.getResponses().get(0).getSchema().get("type"));
        assertEquals("string", ((Map<?, ?>) dynamic.getResponses().get(0).getSchema().get("items")).get("type"));
    }

    @Test
    void retainsComparableDtoPropertiesForControllerSourceParity() {
        ReachHttpApiDescriptor operation = ReachSpringMvcEndpointScanner.scanClass(StructuredSchemaController.class).get(0);

        assertParameter(operation.getParameters(), "id", "PATH", true, "integer");
        Map<?, ?> request = operation.getRequestBody().getSchema();
        assertEquals("object", request.get("type"));
        Map<?, ?> requestProperties = (Map<?, ?>) request.get("properties");
        assertEquals("integer", ((Map<?, ?>) requestProperties.get("quantity")).get("type"));
        assertEquals("string", ((Map<?, ?>) requestProperties.get("reference")).get("type"));

        Map<?, ?> response = operation.getResponses().get(0).getSchema();
        assertEquals("object", response.get("type"));
        Map<?, ?> responseProperties = (Map<?, ?>) response.get("properties");
        assertEquals("integer", ((Map<?, ?>) responseProperties.get("id")).get("type"));
        assertEquals("string", ((Map<?, ?>) responseProperties.get("state")).get("type"));
    }

    @Test
    void rejectsUnannotatedParametersRatherThanGuessingBody() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReachSpringMvcEndpointScanner.scanClass(AmbiguousController.class));

        assertTrue(error.getMessage().contains("Cannot determine Spring MVC parameter binding location"));
    }

    @Test
    void rejectsUnnamedMapCaptureRatherThanManufacturingArgZero() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReachSpringMvcEndpointScanner.scanClass(UnnamedMapController.class));

        assertTrue(error.getMessage().contains("unnamed Spring MVC map capture"));
    }

    @Test
    void retainsMvcOperationWhenTheSameMethodAlsoDeclaresABusinessCapability() {
        List<ReachHttpApiDescriptor> operations = ReachSpringMvcEndpointScanner.scanClass(DualDeclaredController.class);

        assertEquals(1, operations.size());
        assertEquals("GET", operations.get(0).getHttpMethod());
        assertEquals("/dual/current", operations.get(0).getEndpointPath());
        assertTrue(operations.get(0).getSourceKey().startsWith("mvc:"));
    }

    private static Set<String> methods(List<ReachHttpApiDescriptor> operations) {
        Set<String> values = new HashSet<String>();
        for (ReachHttpApiDescriptor operation : operations) {
            values.add(operation.getHttpMethod());
        }
        return values;
    }

    private static ReachHttpApiDescriptor operation(List<ReachHttpApiDescriptor> operations, String endpointPath) {
        for (ReachHttpApiDescriptor operation : operations) {
            if (endpointPath.equals(operation.getEndpointPath())) {
                return operation;
            }
        }
        throw new AssertionError("operation not found: " + endpointPath);
    }

    private static void assertCondition(List<ReachHttpApiMappingCondition> conditions, String kind,
                                        String name, String operator, String value) {
        for (ReachHttpApiMappingCondition condition : conditions) {
            if (kind.equals(condition.getKind()) && name.equals(condition.getName())
                    && operator.equals(condition.getOperator())
                    && java.util.Objects.equals(value, condition.getValue())) {
                return;
            }
        }
        throw new AssertionError("condition not found: " + kind + ":" + name + ":" + operator);
    }

    private static void assertParameter(List<ReachHttpApiParameter> parameters, String name,
                                        String location, boolean required, String type) {
        for (ReachHttpApiParameter parameter : parameters) {
            if (name.equals(parameter.getName()) && location.equals(parameter.getLocation())) {
                assertEquals(required, parameter.isRequired());
                assertEquals(type, parameter.getSchema().get("type"));
                return;
            }
        }
        throw new AssertionError("parameter not found: " + location + " " + name);
    }

    static class PlainBean { }

    @RestController
    @RequestMapping({"/v1", "/v2"})
    static class CartesianController {
        @RequestMapping(value = {"/a", "/b"}, method = {RequestMethod.GET, RequestMethod.POST})
        public String operation() { return ""; }
    }

    @RestController
    @RequestMapping(value = "/method-union", method = RequestMethod.GET)
    static class MethodUnionController {
        @PostMapping("/operation")
        public String operation() { return ""; }
    }

    @RestController
    @RequestMapping(value = "/media", method = RequestMethod.POST,
            headers = {"Content-Type=application/json", "Accept=application/xml", "X-Class"})
    static class ContentHeaderController {
        @RequestMapping(value = "/operation",
                headers = {"Content-Type=application/problem+json", "Accept=application/problem+xml", "X-Method"})
        public String operation() { return ""; }
    }

    @RestController
    @RequestMapping("/unrestricted")
    static class UnrestrictedController {
        @RequestMapping("/operation")
        public String operation() { return ""; }
    }

    @RestController
    @RequestMapping(value = "/conditions", params = "tenant=north", headers = "X-Mode", consumes = "text/plain", produces = "text/plain")
    static class ConditionalController {
        @RequestMapping(value = "/operation", method = RequestMethod.POST, params = "!legacy",
                headers = "X-Version!=v1", consumes = "application/json", produces = "application/problem+json")
        public String operation() { return ""; }
    }

    @RestController
    @RequestMapping("/parameters")
    static class ParameterController {
        @RequestMapping(value = "/{id}", method = RequestMethod.POST, consumes = "application/json")
        public String operation(@PathVariable("id") long id,
                                @RequestParam(name = "q", required = false) String query,
                                @RequestParam(name = "filter", required = false) Filter filter,
                                @RequestHeader("X-Trace") String trace,
                                @CookieValue(name = "session", required = false) String session,
                                @RequestBody List<String> payload) { return ""; }
    }

    static class Filter { }

    @RestController
    @RequestMapping("/optional-input")
    static class OptionalInputController {
        @PostMapping(value = "/current", consumes = "application/json")
        public String operation(@RequestParam(name = "limit", defaultValue = "20") String limit,
                                @RequestHeader(name = "X-Tenant", defaultValue = "default") String tenant,
                                @CookieValue(name = "region", defaultValue = "default") String region,
                                @RequestParam(name = "page") Optional<Integer> page,
                                @RequestBody Optional<List<String>> payload) { return ""; }
    }

    @RestController
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequestMapping("/responses")
    static class ResponseController {
        @PostMapping("/created")
        @ResponseStatus(HttpStatus.CREATED)
        public ResponseEntity<List<String>> created() { return null; }

        @GetMapping("/class-fallback")
        public HttpEntity<Optional<Filter>> classFallback() { return null; }

    }

    @RestController
    static class DynamicResponseController {
        @GetMapping("/dynamic")
        public ResponseEntity<Optional<List<String>>> dynamic() { return null; }
    }

    @RestController
    @RequestMapping("/schema")
    static class StructuredSchemaController {
        @PostMapping(value = "/orders/{id}", consumes = "application/json", produces = "application/json")
        ResponseEntity<StructuredSchemaResponse> update(@PathVariable("id") long id,
                                                        @RequestBody StructuredSchemaRequest request) {
            return null;
        }
    }

    static class StructuredSchemaRequest {
        long quantity;
        String reference;
    }

    static class StructuredSchemaResponse {
        long id;
        String state;
    }

    @RestController
    static class AmbiguousController {
        @GetMapping("/ambiguous")
        public String operation(String unsafeGuess) { return unsafeGuess; }
    }

    @RestController
    static class UnnamedMapController {
        @GetMapping("/all")
        public String operation(@RequestParam Map<String, String> all) { return ""; }
    }

    @RestController
    @RequestMapping("/dual")
    static class DualDeclaredController {
        @ReachCapability(name = "team.current")
        @GetMapping("/current")
        public String operation() { return ""; }
    }
}
