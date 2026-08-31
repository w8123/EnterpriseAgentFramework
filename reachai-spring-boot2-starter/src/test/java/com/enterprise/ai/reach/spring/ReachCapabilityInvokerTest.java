package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachCapabilityInvokerTest {

    @Test
    void invokesAnnotatedBeanMethodByCapabilityName() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new ContractCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("contractNo", "HT-001");

        Object result = invoker.invoke("contract.query", arguments);

        assertEquals("contract:HT-001", result);
    }

    @Test
    void bindsFlatToolInputToSingleDtoWithAnnotatedFields() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new MemoryCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("resourceType", "team");
        arguments.put("resourceId", "TI-001");

        Object result = invoker.invoke("team.memory.resolve", arguments);

        assertEquals("team:TI-001", result);
    }

    @Test
    void stillAcceptsExplicitlyWrappedDtoInput() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new MemoryCapability()});
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("resourceType", "team");
        request.put("resourceId", "TI-002");
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("request", request);

        Object result = invoker.invoke("team.memory.resolve", arguments);

        assertEquals("team:TI-002", result);
    }

    @Test
    void usesTrimmedExplicitCapabilityName() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new TrimmedCapability()});

        Object result = invoker.invoke("trimmed.op", null);

        assertEquals("trimmed", result);
    }

    @Test
    void fallsBackToMethodNameWhenCapabilityNameIsBlank() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new BlankNameCapability()});

        Object result = invoker.invoke("fallbackOp", null);

        assertEquals("fallback", result);
    }

    @Test
    void invokesPrivateAnnotatedMethod() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new PrivateCapability()});

        Object result = invoker.invoke("private.op", null);

        assertEquals("secret", result);
    }

    @Test
    void invokesZeroArgumentMethodWithNullArguments() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new ZeroArgCapability()});

        Object result = invoker.invoke("zero.op", null);

        assertEquals("pong", result);
    }

    @Test
    void bindsMultipleParametersByReachParamAndCompilerNames() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new MultiParamCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("first", "A");
        arguments.put("second", "B");

        Object result = invoker.invoke("multi.op", arguments);

        assertEquals("A:B", result);
    }

    @Test
    void fallsBackToCompilerNameWhenReachParamNameIsBlank() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new BlankParamCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("value", "x");

        Object result = invoker.invoke("blank.param", arguments);

        assertEquals("x", result);
    }

    @Test
    void convertsScalarArgumentsToDeclaredTypes() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new ScalarCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("count", "42");
        arguments.put("flag", "true");

        Object result = invoker.invoke("scalar.op", arguments);

        assertEquals("42:true", result);
    }

    @Test
    void passesNullForMissingAndExplicitNullArguments() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new NullableCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("a", null);

        Object result = invoker.invoke("nullable.op", arguments);

        assertEquals("true:true", result);
    }

    @Test
    void rejectsUnknownCapabilityWithRequestedName() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new TrimmedCapability()});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invoker.invoke("does.not.exist", null));

        assertTrue(ex.getMessage().contains("does.not.exist"));
    }

    @Test
    void wrapsTargetFailureAndRetainsOriginalCause() {
        FailingCapability capability = new FailingCapability();
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{capability});

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invoker.invoke("fail.op", null));

        assertTrue(ex.getMessage().contains("fail.op"));
        assertTrue(hasCause(ex, capability.failure));
    }

    @Test
    void wrapsArgumentConversionFailureWithCapabilityContext() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new ConvertFailCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("count", "not-a-number");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invoker.invoke("convertfail.op", arguments));

        assertTrue(ex.getMessage().contains("convertfail.op"));
        assertTrue(hasCauseOfType(ex, IllegalArgumentException.class));
    }

    @Test
    void explicitDtoWrapperWinsWhenFlatFieldsAreAlsoPresent() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new WrapperCapability()});
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put("id", "wrapper-id");
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("request", wrapper);
        arguments.put("id", "flat-id");

        Object result = invoker.invoke("wrapper.op", arguments);

        assertEquals("wrapper-id", result);
    }

    @Test
    void doesNotFlattenSingleDtoWithoutAnnotatedFields() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new NoAnnotatedDtoCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("value", "v");

        Object result = invoker.invoke("noannotated.op", arguments);

        assertEquals("null", result);
    }

    @Test
    void detectsAnnotatedDtoFieldsDeclaredOnSuperclassForFlatBinding() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new SubclassDtoCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("name", "N");
        arguments.put("category", "C");

        Object result = invoker.invoke("subclass.op", arguments);

        assertEquals("N:C", result);
    }

    @Test
    void doesNotFlattenWholeInputForDtoWhenMethodHasMultipleParameters() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new MultiDtoCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("id", "flat-id");
        arguments.put("label", "L");

        Object result = invoker.invoke("multidto.op", arguments);

        assertEquals("null:L", result);
    }

    @Test
    void hasTagTrimsDeclaredAndExpectedText() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new TaggedCapability()});

        assertTrue(invoker.hasTag("tagged.op", "  alpha  "));
        assertTrue(invoker.hasTag("tagged.op", "beta"));
    }

    @Test
    void hasTagReturnsFalseForUnknownBlankAndCaseMismatch() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new TaggedCapability()});

        assertFalse(invoker.hasTag("unknown.op", "alpha"));
        assertFalse(invoker.hasTag("tagged.op", ""));
        assertFalse(invoker.hasTag("tagged.op", "   "));
        assertFalse(invoker.hasTag("tagged.op", null));
        assertFalse(invoker.hasTag("tagged.op", "ALPHA"));
    }

    @Test
    void skipsInfrastructureBeansAndInvokesThroughCglibProxyTargetClass() {
        ProxyCapability target = new ProxyCapability();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        Object proxy = factory.getProxy();
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{
                null,
                new AnnotatedInfrastructureInvoker(),
                proxy
        });

        Object result = invoker.invoke("proxy.op", null);

        assertEquals("proxied", result);
        assertThrows(IllegalArgumentException.class,
                () -> invoker.invoke("infrastructure.op", null));
    }

    private static boolean hasCause(Throwable t, Throwable expected) {
        Throwable current = t;
        while (current != null) {
            if (current == expected) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasCauseOfType(Throwable t, Class<? extends Throwable> expectedType) {
        Throwable current = t;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static class ContractCapability {
        @ReachCapability(name = "contract.query")
        public String query(@ReachParam(name = "contractNo", required = true) String contractNo) {
            return "contract:" + contractNo;
        }
    }

    static class MemoryCapability {
        @ReachCapability(name = "team.memory.resolve")
        public String resolve(@ReachParam(name = "request", required = true) MemoryRequest request) {
            return request.getResourceType() + ":" + request.getResourceId();
        }
    }

    static class MemoryRequest {
        @ReachParam(name = "resourceType", required = true)
        private String resourceType;

        @ReachParam(name = "resourceId", required = true)
        private String resourceId;

        public String getResourceType() {
            return resourceType;
        }

        public void setResourceType(String resourceType) {
            this.resourceType = resourceType;
        }

        public String getResourceId() {
            return resourceId;
        }

        public void setResourceId(String resourceId) {
            this.resourceId = resourceId;
        }
    }

    static class TrimmedCapability {
        @ReachCapability(name = "  trimmed.op  ")
        public String run() {
            return "trimmed";
        }
    }

    static class BlankNameCapability {
        @ReachCapability(name = "   ")
        public String fallbackOp() {
            return "fallback";
        }
    }

    static class PrivateCapability {
        @ReachCapability(name = "private.op")
        private String secret() {
            return "secret";
        }
    }

    static class ZeroArgCapability {
        @ReachCapability(name = "zero.op")
        public String ping() {
            return "pong";
        }
    }

    static class MultiParamCapability {
        @ReachCapability(name = "multi.op")
        public String combine(@ReachParam(name = "first", required = true) String first, String second) {
            return first + ":" + second;
        }
    }

    static class BlankParamCapability {
        @ReachCapability(name = "blank.param")
        public String echo(@ReachParam(name = "   ", required = true) String value) {
            return value;
        }
    }

    static class ScalarCapability {
        @ReachCapability(name = "scalar.op")
        public String convert(@ReachParam(name = "count", required = true) int count,
                              @ReachParam(name = "flag", required = true) boolean flag) {
            return count + ":" + flag;
        }
    }

    static class NullableCapability {
        @ReachCapability(name = "nullable.op")
        public String probe(String a, String b) {
            return (a == null) + ":" + (b == null);
        }
    }

    static class FailingCapability {
        private final RuntimeException failure = new RuntimeException("boom-inner");

        @ReachCapability(name = "fail.op")
        public String explode() {
            throw failure;
        }
    }

    static class ConvertFailCapability {
        @ReachCapability(name = "convertfail.op")
        public String process(@ReachParam(name = "count", required = true) int count) {
            return "never-" + count;
        }
    }

    static class WrapperCapability {
        @ReachCapability(name = "wrapper.op")
        public String resolve(@ReachParam(name = "request", required = true) WrapperRequest request) {
            return request.getId();
        }
    }

    static class WrapperRequest {
        @ReachParam(name = "id", required = true)
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    static class NoAnnotatedDtoCapability {
        @ReachCapability(name = "noannotated.op")
        public String process(@ReachParam(name = "payload", required = true) PlainRequest payload) {
            return payload == null ? "null" : payload.getValue();
        }
    }

    static class PlainRequest {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    static class SubclassDtoCapability {
        @ReachCapability(name = "subclass.op")
        public String process(@ReachParam(name = "payload", required = true) SubclassRequest payload) {
            return payload.getName() + ":" + payload.getCategory();
        }
    }

    static class BaseRequest {
        @ReachParam(name = "name", required = true)
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class SubclassRequest extends BaseRequest {
        private String category;

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }
    }

    static class MultiDtoCapability {
        @ReachCapability(name = "multidto.op")
        public String process(@ReachParam(name = "payload", required = true) AnnotatedRequest payload, String label) {
            return (payload == null ? "null" : payload.getId()) + ":" + label;
        }
    }

    static class AnnotatedRequest {
        @ReachParam(name = "id", required = true)
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    static class TaggedCapability {
        @ReachCapability(name = "tagged.op", tags = {" alpha ", " beta "})
        public String run() {
            return "ok";
        }
    }

    static class ProxyCapability {
        @ReachCapability(name = "proxy.op")
        public String run() {
            return "proxied";
        }
    }

    static class AnnotatedInfrastructureInvoker extends ReachCapabilityInvoker {
        AnnotatedInfrastructureInvoker() {
            super(new Object[0]);
        }

        @ReachCapability(name = "infrastructure.op")
        public String mustNotBeIndexed() {
            return "unexpected";
        }
    }
}
