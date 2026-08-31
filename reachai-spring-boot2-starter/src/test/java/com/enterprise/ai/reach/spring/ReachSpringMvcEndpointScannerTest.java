package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityParameter;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachSpringMvcEndpointScannerTest {

    @Test
    void returnsEmptyForNullAndNonControllerTypes() {
        assertEquals(0, ReachSpringMvcEndpointScanner.scanClass(null).size());
        assertEquals(0, ReachSpringMvcEndpointScanner.scanClass(PlainBean.class).size());
    }

    @Test
    void combinesAndNormalizesClassAndMethodPaths() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(OrderController.class);

        assertEquals(1, descriptors.size());
        assertEquals("/api/orders/{id}", descriptors.get(0).getEndpointPath());
        assertEquals("api_orders_id", descriptors.get(0).getName());
        assertEquals("api", descriptors.get(0).getDomain());
    }

    @Test
    void createsCrossProductForMultipleClassAndMethodPaths() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(MultiPathController.class);

        Set<String> expected = new HashSet<String>(Arrays.asList("/v1/a", "/v1/b", "/v2/a", "/v2/b"));
        assertEquals(expected, endpointPaths(descriptors));
        assertEquals(4, descriptors.size());
    }

    @Test
    void mapsComposedHttpMethodsAndSideEffects() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(HttpMethodController.class);

        assertEquals(5, descriptors.size());
        assertEquals("GET", findByMethodName(descriptors, "get").getHttpMethod());
        assertEquals(ReachSideEffectLevel.READ, findByMethodName(descriptors, "get").getSideEffect());
        assertEquals("POST", findByMethodName(descriptors, "post").getHttpMethod());
        assertEquals(ReachSideEffectLevel.WRITE, findByMethodName(descriptors, "post").getSideEffect());
        assertEquals("PUT", findByMethodName(descriptors, "put").getHttpMethod());
        assertEquals(ReachSideEffectLevel.WRITE, findByMethodName(descriptors, "put").getSideEffect());
        assertEquals("DELETE", findByMethodName(descriptors, "delete").getHttpMethod());
        assertEquals(ReachSideEffectLevel.WRITE, findByMethodName(descriptors, "delete").getSideEffect());
        assertEquals("PATCH", findByMethodName(descriptors, "patch").getHttpMethod());
        assertEquals(ReachSideEffectLevel.WRITE, findByMethodName(descriptors, "patch").getSideEffect());
    }

    @Test
    void requestMappingDefaultsToGetAndUsesFirstDeclaredMethod() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(RequestMappingController.class);

        assertEquals(2, descriptors.size());
        assertEquals("GET", findByMethodName(descriptors, "defaulted").getHttpMethod());
        assertEquals("POST", findByMethodName(descriptors, "multi").getHttpMethod());
    }

    @Test
    void skipsMethodsAlreadyDeclaredAsReachCapabilities() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(SkippedController.class);

        assertEquals(0, descriptors.size());
    }

    @Test
    void usesMethodNameForTitleAndDescriptionWithoutSwagger() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(TitleController.class);

        ReachCapabilityDescriptor descriptor = findByMethodName(descriptors, "doSomething");
        assertEquals("doSomething", descriptor.getTitle());
        assertEquals("doSomething", descriptor.getDescription());
    }

    @Test
    void mapsDescriptorIdentityTagsAndReturnType() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(IdentityController.class);

        ReachCapabilityDescriptor descriptor = findByMethodName(descriptors, "list");
        assertEquals("ident_list", descriptor.getName());
        assertEquals("ident", descriptor.getDomain());
        assertEquals("IdentityController", descriptor.getModule());
        assertEquals(Arrays.asList("Spring MVC", "GET"), descriptor.getTags());
        assertEquals(IdentityController.class.getName(), descriptor.getClassName());
        assertEquals("list", descriptor.getMethodName());
        assertEquals("GET", descriptor.getHttpMethod());
        assertEquals("/ident/list", descriptor.getEndpointPath());
        assertEquals("java.util.List<java.lang.String>", descriptor.getReturnType());
    }

    @Test
    void resolvesRequestParamNamesByDeclaredPriority() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(RequestParamController.class);

        List<ReachCapabilityParameter> parameters = findByMethodName(descriptors, "query").getParameters();
        assertEquals(3, parameters.size());
        assertEquals("aName", paramByName(parameters, "aName").getName());
        assertEquals("bValue", paramByName(parameters, "bValue").getName());
        assertEquals("fromCompileName", paramByName(parameters, "fromCompileName").getName());
    }

    @Test
    void resolvesPathVariableNamesByDeclaredPriority() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(PathVariableController.class);

        List<ReachCapabilityParameter> parameters = findByMethodName(descriptors, "resolve").getParameters();
        assertEquals(3, parameters.size());
        assertEquals("nameId", paramByName(parameters, "nameId").getName());
        assertEquals("valueId", paramByName(parameters, "valueId").getName());
        assertEquals("fromCompileName", paramByName(parameters, "fromCompileName").getName());
    }

    @Test
    void mapsStringBooleanNumberAndObjectParameterTypes() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(TypeController.class);

        List<ReachCapabilityParameter> parameters = findByMethodName(descriptors, "map").getParameters();
        assertEquals(8, parameters.size());
        assertEquals("string", paramByName(parameters, "text").getType());
        assertEquals("boolean", paramByName(parameters, "flag").getType());
        assertEquals("number", paramByName(parameters, "count").getType());
        assertEquals("string", paramByName(parameters, "letter").getType());
        assertEquals("string", paramByName(parameters, "primitiveLetter").getType());
        assertEquals("boolean", paramByName(parameters, "boxedFlag").getType());
        assertEquals("number", paramByName(parameters, "boxedCount").getType());
        assertEquals("object", paramByName(parameters, "payload").getType());
    }

    @Test
    void mapsRequestParamRequiredFlagAndDefaultsOtherParametersToRequired() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(RequiredController.class);

        List<ReachCapabilityParameter> parameters = findByMethodName(descriptors, "m").getParameters();
        assertFalse(paramByName(parameters, "optional").isRequired());
        assertTrue(paramByName(parameters, "must").isRequired());
        assertTrue(paramByName(parameters, "plain").isRequired());
        assertTrue(paramByName(parameters, "implicit").isRequired());
    }

    @Test
    void capturesGenericRequestBodyTypeAndLeavesItNullOtherwise() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(BodyController.class);

        assertEquals("java.util.List<java.lang.String>", findByMethodName(descriptors, "create").getRequestBodyType());
        assertNull(findByMethodName(descriptors, "get").getRequestBodyType());
    }

    @Test
    void usesRootPathAndMethodNameFallbackWhenMappingsHaveNoPaths() {
        List<ReachCapabilityDescriptor> descriptors = ReachSpringMvcEndpointScanner.scanClass(NoPathController.class);

        assertEquals(1, descriptors.size());
        ReachCapabilityDescriptor descriptor = descriptors.get(0);
        assertEquals("/", descriptor.getEndpointPath());
        assertNull(descriptor.getDomain());
        assertEquals("list", descriptor.getName());
    }

    private static ReachCapabilityDescriptor findByMethodName(List<ReachCapabilityDescriptor> descriptors, String methodName) {
        for (ReachCapabilityDescriptor descriptor : descriptors) {
            if (methodName.equals(descriptor.getMethodName())) {
                return descriptor;
            }
        }
        throw new AssertionError("No descriptor with methodName=" + methodName);
    }

    private static ReachCapabilityParameter paramByName(List<ReachCapabilityParameter> parameters, String name) {
        for (ReachCapabilityParameter parameter : parameters) {
            if (name.equals(parameter.getName())) {
                return parameter;
            }
        }
        throw new AssertionError("No parameter with name=" + name);
    }

    private static Set<String> endpointPaths(List<ReachCapabilityDescriptor> descriptors) {
        Set<String> paths = new HashSet<String>();
        for (ReachCapabilityDescriptor descriptor : descriptors) {
            paths.add(descriptor.getEndpointPath());
        }
        return paths;
    }

    static class PlainBean {
        public String notAnEndpoint() {
            return "";
        }
    }

    @RestController
    @RequestMapping("  api/  ")
    static class OrderController {
        @GetMapping("  orders/{id}/  ")
        public String getOrder() {
            return "";
        }
    }

    @RestController
    @RequestMapping({"/v1/", "/v2/"})
    static class MultiPathController {
        @GetMapping({"/a/", "/b/"})
        public String ab() {
            return "";
        }
    }

    @RestController
    @RequestMapping("/m")
    static class HttpMethodController {
        @GetMapping("/g")
        public String get() {
            return "";
        }

        @PostMapping("/p")
        public String post() {
            return "";
        }

        @PutMapping("/u")
        public String put() {
            return "";
        }

        @DeleteMapping("/d")
        public String delete() {
            return "";
        }

        @PatchMapping("/pt")
        public String patch() {
            return "";
        }
    }

    @RestController
    @RequestMapping("/rm")
    static class RequestMappingController {
        @RequestMapping("/def")
        public String defaulted() {
            return "";
        }

        @RequestMapping(value = "/multi", method = {RequestMethod.POST, RequestMethod.PUT})
        public String multi() {
            return "";
        }
    }

    @RestController
    @RequestMapping("/skip")
    static class SkippedController {
        @ReachCapability(name = "skip.explicit")
        @GetMapping("/both")
        public String both() {
            return "";
        }
    }

    @RestController
    @RequestMapping("/t")
    static class TitleController {
        @GetMapping("/x")
        public String doSomething() {
            return "";
        }
    }

    @RestController
    @RequestMapping("/ident")
    static class IdentityController {
        @GetMapping("/list")
        public List<String> list() {
            return new ArrayList<String>();
        }
    }

    @RestController
    @RequestMapping("/rp")
    static class RequestParamController {
        @GetMapping("/q")
        public String query(@RequestParam(name = "  aName  ") String first,
                            @RequestParam(value = "  bValue  ") String second,
                            @RequestParam String fromCompileName) {
            return "";
        }
    }

    @RestController
    @RequestMapping("/pv")
    static class PathVariableController {
        @GetMapping("/{a}/{b}/{c}")
        public String resolve(@PathVariable(name = "  nameId  ") String first,
                              @PathVariable(value = "  valueId  ") String second,
                              @PathVariable String fromCompileName) {
            return "";
        }
    }

    @RestController
    @RequestMapping("/types")
    static class TypeController {
        @GetMapping("/m")
        public String map(@RequestParam String text,
                          @RequestParam boolean flag,
                          @RequestParam int count,
                          @RequestParam Character letter,
                          @RequestParam char primitiveLetter,
                          @RequestParam Boolean boxedFlag,
                          @RequestParam Long boxedCount,
                          @RequestParam Object payload) {
            return "";
        }
    }

    @RestController
    @RequestMapping("/req")
    static class RequiredController {
        @GetMapping("/m")
        public String m(@RequestParam(required = false) String optional,
                        @RequestParam(required = true) String must,
                        @RequestParam String plain,
                        String implicit) {
            return "";
        }
    }

    @RestController
    @RequestMapping("/body")
    static class BodyController {
        @PostMapping("/create")
        public String create(@RequestBody List<String> payload) {
            return "";
        }

        @GetMapping("/get")
        public String get(String plain) {
            return "";
        }
    }

    @RestController
    static class NoPathController {
        @GetMapping
        public String list() {
            return "";
        }
    }
}
