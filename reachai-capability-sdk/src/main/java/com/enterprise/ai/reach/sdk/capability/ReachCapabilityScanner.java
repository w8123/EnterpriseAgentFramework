package com.enterprise.ai.reach.sdk.capability;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
        descriptor.setAssetType(ReachCapabilityAssetType.BUSINESS_METHOD);
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
        Type[] genericTypes = method.getGenericParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ReachParam reachParam = parameter.getAnnotation(ReachParam.class);
            String name = reachParam != null && reachParam.name() != null && !reachParam.name().trim().isEmpty()
                    ? reachParam.name().trim()
                    : parameter.isNamePresent() ? parameter.getName() : "arg" + i;
            out.add(parameterDescriptor(name, genericTypes[i], parameter.getType(), reachParam));
            out.addAll(fieldParameters(name, genericTypes[i], parameter.getType()));
        }
        return out;
    }

    private static List<ReachCapabilityParameter> fieldParameters(String parentName, Type genericType, Class<?> type) {
        List<ReachCapabilityParameter> out = new ArrayList<ReachCapabilityParameter>();
        if (isSimpleType(type) || isContainerType(type)) {
            return out;
        }
        for (Field field : type.getDeclaredFields()) {
            ReachParam annotation = field.getAnnotation(ReachParam.class);
            if (annotation == null) {
                continue;
            }
            out.add(parameterDescriptor(parentName + "." + field.getName(), field.getGenericType(), field.getType(), annotation));
        }
        return out;
    }

    private static ReachCapabilityParameter parameterDescriptor(String name, Type genericType, Class<?> type, ReachParam annotation) {
        ReachCapabilityParameter parameter = new ReachCapabilityParameter();
        parameter.setName(name);
        parameter.setType(typeName(type));
        parameter.setItemsType(itemType(genericType));
        parameter.setOpenObject(type != null && Map.class.isAssignableFrom(type));
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

    private static boolean isContainerType(Class<?> type) {
        return type != null && (type.isArray()
                || Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type));
    }

    private static String typeName(Class<?> type) {
        if (type == null) {
            return "object";
        }
        if (type.isArray() || Iterable.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        if (Map.class.isAssignableFrom(type)) {
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

    private static String itemType(Type genericType) {
        if (genericType instanceof Class) {
            Class<?> type = (Class<?>) genericType;
            return type.isArray() ? typeName(type.getComponentType()) : null;
        }
        if (genericType instanceof GenericArrayType) {
            return typeName(rawClass(((GenericArrayType) genericType).getGenericComponentType()));
        }
        if (genericType instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) genericType;
            Class<?> raw = rawClass(parameterized.getRawType());
            if (raw != null && Iterable.class.isAssignableFrom(raw)) {
                Type[] arguments = parameterized.getActualTypeArguments();
                return arguments.length == 0 ? null : typeName(rawClass(arguments[0]));
            }
        }
        return null;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return rawClass(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = rawClass(((GenericArrayType) type).getGenericComponentType());
            return component == null ? null : java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        return null;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
