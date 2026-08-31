package com.enterprise.ai.reach.sdk.capability;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scans the declared methods of the given classes and emits one descriptor per
 * method annotated with {@code @ReachCapability}. Non-annotated methods and
 * null classes are ignored. Complex parameters are expanded one level into
 * their directly declared {@code @ReachParam} fields only; no recursion.
 */
public final class ReachCapabilityScanner {

    private ReachCapabilityScanner() {
    }

    public static List<ReachCapabilityDescriptor> scanClasses(Class<?>... types) {
        List<ReachCapabilityDescriptor> descriptors = new ArrayList<ReachCapabilityDescriptor>();
        if (types == null) {
            return descriptors;
        }
        for (Class<?> type : types) {
            if (type == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                ReachCapability capability = method.getAnnotation(ReachCapability.class);
                if (capability != null) {
                    descriptors.add(descriptor(type, method, capability));
                }
            }
        }
        return descriptors;
    }

    private static ReachCapabilityDescriptor descriptor(Class<?> type, Method method, ReachCapability capability) {
        ReachCapabilityDescriptor descriptor = new ReachCapabilityDescriptor();
        descriptor.setName(textOr(capability.name(), method.getName()));
        descriptor.setTitle(trimToNull(capability.title()));
        descriptor.setDescription(trimToNull(capability.description()));
        descriptor.setDomain(trimToNull(capability.domain()));
        descriptor.setModule(trimToNull(capability.module()));
        descriptor.setTags(Arrays.asList(capability.tags()));
        descriptor.setSideEffect(capability.sideEffect());
        descriptor.setRequiredRoles(Arrays.asList(capability.requiredRoles()));
        descriptor.setTimeoutMs(capability.timeoutMs());
        descriptor.setRetryLimit(capability.retryLimit());
        descriptor.setClassName(type.getName());
        descriptor.setMethodName(method.getName());
        descriptor.setReturnType(method.getReturnType().getName());
        descriptor.setParameters(parameters(method));
        return descriptor;
    }

    private static List<ReachCapabilityParameter> parameters(Method method) {
        List<ReachCapabilityParameter> out = new ArrayList<ReachCapabilityParameter>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ReachParam reachParam = parameter.getAnnotation(ReachParam.class);
            String name = reachParam != null && reachParam.name() != null && !reachParam.name().trim().isEmpty()
                    ? reachParam.name().trim()
                    : parameter.isNamePresent() ? parameter.getName() : "arg" + i;
            out.add(parameterDescriptor(name, parameter.getType(), reachParam));
            out.addAll(fieldParameters(name, parameter.getType()));
        }
        return out;
    }

    private static List<ReachCapabilityParameter> fieldParameters(String parentName, Class<?> type) {
        List<ReachCapabilityParameter> out = new ArrayList<ReachCapabilityParameter>();
        if (isSimpleType(type)) {
            return out;
        }
        for (Field field : type.getDeclaredFields()) {
            ReachParam annotation = field.getAnnotation(ReachParam.class);
            if (annotation == null) {
                continue;
            }
            out.add(parameterDescriptor(parentName + "." + field.getName(), field.getType(), annotation));
        }
        return out;
    }

    private static ReachCapabilityParameter parameterDescriptor(String name, Class<?> type, ReachParam annotation) {
        ReachCapabilityParameter parameter = new ReachCapabilityParameter();
        parameter.setName(name);
        parameter.setType(typeName(type));
        if (annotation != null) {
            parameter.setRequired(annotation.required());
            parameter.setDescription(trimToNull(annotation.description()));
            parameter.setExample(trimToNull(annotation.example()));
            parameter.setSourceHint(trimToNull(annotation.sourceHint()));
            parameter.setDictType(trimToNull(annotation.dictType()));
            parameter.setSensitive(annotation.sensitive());
        }
        return parameter;
    }

    private static boolean isSimpleType(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isEnum()) {
            return true;
        }
        String name = type.getName();
        return name.startsWith("java.lang.")
                || name.startsWith("java.math.")
                || name.startsWith("java.time.")
                || "java.util.Date".equals(name);
    }

    private static String typeName(Class<?> type) {
        if (type == null) {
            return "object";
        }
        if (String.class.equals(type) || Character.class.equals(type) || char.class.equals(type)) {
            return "string";
        }
        if (Boolean.class.equals(type) || boolean.class.equals(type)) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(type)
                || byte.class.equals(type)
                || short.class.equals(type)
                || int.class.equals(type)
                || long.class.equals(type)
                || float.class.equals(type)
                || double.class.equals(type)) {
            return "number";
        }
        return "object";
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
