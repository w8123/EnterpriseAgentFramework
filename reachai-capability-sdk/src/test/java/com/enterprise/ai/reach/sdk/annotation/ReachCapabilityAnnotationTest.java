package com.enterprise.ai.reach.sdk.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachCapabilityAnnotationTest {

    static class ContractApi {
        @ReachCapability(
                name = "contract.query",
                title = "查询合同",
                description = "根据合同编号查询合同详情",
                domain = "contract",
                module = "review",
                sideEffect = ReachSideEffectLevel.READ,
                requiredRoles = {"contract_reader"})
        String query(@ReachParam(description = "合同编号", required = true, example = "HT-2026-0001") String contractNo) {
            return contractNo;
        }
    }

    @Test
    void reachCapabilityIsRuntimeMethodAnnotation() throws Exception {
        Retention retention = ReachCapability.class.getAnnotation(Retention.class);
        Target target = ReachCapability.class.getAnnotation(Target.class);

        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD));

        Method method = ContractApi.class.getDeclaredMethod("query", String.class);
        ReachCapability capability = method.getAnnotation(ReachCapability.class);
        assertEquals("contract.query", capability.name());
        assertEquals("查询合同", capability.title());
        assertEquals(ReachSideEffectLevel.READ, capability.sideEffect());
        assertEquals("contract_reader", capability.requiredRoles()[0]);
    }

    @Test
    void reachParamIsRuntimeParameterAndFieldAnnotationWithoutRecordComponentTarget() {
        Retention retention = ReachParam.class.getAnnotation(Retention.class);
        Target target = ReachParam.class.getAnnotation(Target.class);

        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertTrue(Arrays.asList(target.value()).contains(ElementType.PARAMETER));
        assertTrue(Arrays.asList(target.value()).contains(ElementType.FIELD));
    }

    static class DefaultsApi {
        @ReachCapability
        String probe(@ReachParam String p) {
            return p;
        }
    }

    static class FieldProbe {
        @ReachOutput
        String out;
    }

    @Test
    void reachOutputIsRuntimeFieldAnnotation() {
        Retention retention = ReachOutput.class.getAnnotation(Retention.class);
        Target target = ReachOutput.class.getAnnotation(Target.class);

        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertEquals(1, target.value().length);
        assertEquals(ElementType.FIELD, target.value()[0]);
    }

    @Test
    void reachCapabilityDefaultsRemainStable() throws Exception {
        ReachCapability capability = DefaultsApi.class
                .getDeclaredMethod("probe", String.class)
                .getAnnotation(ReachCapability.class);

        assertEquals("", capability.name());
        assertEquals("", capability.title());
        assertEquals("", capability.description());
        assertEquals("", capability.domain());
        assertEquals("", capability.module());
        assertEquals(0, capability.tags().length);
        assertEquals(0, capability.requiredRoles().length);
        assertEquals(ReachSideEffectLevel.WRITE, capability.sideEffect());
        assertEquals(0, capability.timeoutMs());
        assertEquals(-1, capability.retryLimit());
    }

    @Test
    void reachParamAndReachOutputDefaultsRemainStable() throws Exception {
        Parameter parameter = DefaultsApi.class
                .getDeclaredMethod("probe", String.class)
                .getParameters()[0];
        ReachParam reachParam = parameter.getAnnotation(ReachParam.class);

        assertEquals("", reachParam.name());
        assertEquals("", reachParam.description());
        assertEquals("", reachParam.example());
        assertEquals("", reachParam.sourceHint());
        assertEquals("", reachParam.dictType());
        assertFalse(reachParam.required());
        assertFalse(reachParam.sensitive());

        Field field = FieldProbe.class.getDeclaredField("out");
        ReachOutput output = field.getAnnotation(ReachOutput.class);

        assertEquals("", output.description());
        assertEquals("", output.example());
        assertFalse(output.sensitive());
    }
}
