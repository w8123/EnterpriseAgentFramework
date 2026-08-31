package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryResolution;
import com.enterprise.ai.reach.sdk.memory.ReachBusinessMemoryResolverRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lazily indexes explicit {@code @ReachCapability} methods from the supplied
 * beans or application context, converts invocation arguments to each
 * parameter's declared type by name, and supports a flat Tool input for a
 * single DTO that exposes {@code @ReachParam} fields.
 */
public class ReachCapabilityInvoker {

    private final Map<String, Handler> handlers = new LinkedHashMap<String, Handler>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationContext applicationContext;
    private final Object[] directBeans;
    private boolean initialized;

    public ReachCapabilityInvoker(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.directBeans = null;
    }

    ReachCapabilityInvoker(Object[] beans) {
        this.applicationContext = null;
        this.directBeans = beans;
    }

    public Object invoke(String capabilityName, Map<String, Object> arguments) {
        ensureInitialized();
        Handler handler = handlers.get(capabilityName);
        if (handler == null) {
            throw new IllegalArgumentException("ReachAI capability not found: " + capabilityName);
        }
        try {
            return handler.method.invoke(handler.bean, argumentValues(handler, arguments));
        } catch (Exception e) {
            throw new IllegalStateException("ReachAI capability invocation failed: " + capabilityName, e);
        }
    }

    public boolean hasTag(String capabilityName, String expectedTag) {
        ensureInitialized();
        Handler handler = handlers.get(capabilityName);
        if (handler == null || !StringUtils.hasText(expectedTag)) {
            return false;
        }
        for (String tag : handler.capability.tags()) {
            if (StringUtils.hasText(tag) && expectedTag.trim().equals(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    public boolean requiresBusinessMemoryGuard(String capabilityName) {
        ensureInitialized();
        Handler handler = handlers.get(capabilityName);
        if (handler == null) {
            return false;
        }
        if (hasTag(capabilityName, ReachBusinessMemoryResolverGuard.TAG)
                || ReachBusinessMemoryResolution.class.isAssignableFrom(handler.method.getReturnType())) {
            return true;
        }
        for (Class<?> parameterType : handler.parameterTypes) {
            if (ReachBusinessMemoryResolverRequest.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }
        return false;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        Object[] beans = directBeans;
        if (beans == null && applicationContext != null) {
            beans = applicationContext.getBeansOfType(Object.class, false, false).values().toArray();
        }
        if (beans != null) {
            for (Object bean : beans) {
                indexBean(bean);
            }
        }
        initialized = true;
    }

    private Object[] argumentValues(Handler handler, Map<String, Object> arguments) {
        Object[] values = new Object[handler.parameterTypes.length];
        Map<String, Object> source = arguments == null
                ? new LinkedHashMap<String, Object>()
                : arguments;
        for (int i = 0; i < handler.parameterTypes.length; i++) {
            String parameterName = handler.parameterNames[i];
            Object raw = source.get(parameterName);
            if (!source.containsKey(parameterName)
                    && handler.parameterTypes.length == 1
                    && hasAnnotatedInputFields(handler.parameterTypes[i])) {
                // ReachAI Tool input is a flat object. A single DTO whose fields carry
                // @ReachParam metadata represents that object even when the Java method
                // gives the parameter a wrapper name such as "request".
                raw = source;
            }
            values[i] = raw == null ? null : objectMapper.convertValue(raw, handler.parameterTypes[i]);
        }
        return values;
    }

    private boolean hasAnnotatedInputFields(Class<?> parameterType) {
        Class<?> current = parameterType;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getAnnotation(ReachParam.class) != null) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private void indexBean(Object bean) {
        if (bean == null || isInfrastructureBean(bean)) {
            return;
        }
        Class<?> userClass = AopUtils.getTargetClass(bean);
        for (Method method : userClass.getDeclaredMethods()) {
            ReachCapability capability = method.getAnnotation(ReachCapability.class);
            if (capability == null) {
                continue;
            }
            String name = StringUtils.hasText(capability.name()) ? capability.name().trim() : method.getName();
            method.setAccessible(true);
            handlers.put(name, new Handler(bean, method, capability,
                    parameterNames(method), parameterTypes(method)));
        }
    }

    private String[] parameterNames(Method method) {
        Parameter[] parameters = method.getParameters();
        String[] names = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            ReachParam reachParam = parameters[i].getAnnotation(ReachParam.class);
            names[i] = reachParam != null && StringUtils.hasText(reachParam.name())
                    ? reachParam.name().trim()
                    : parameters[i].isNamePresent() ? parameters[i].getName() : "arg" + i;
        }
        return names;
    }

    private Class<?>[] parameterTypes(Method method) {
        Parameter[] parameters = method.getParameters();
        Class<?>[] types = new Class<?>[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            types[i] = parameters[i].getType();
        }
        return types;
    }

    private boolean isInfrastructureBean(Object bean) {
        String name = bean.getClass().getName();
        return name.startsWith("org.springframework.")
                || bean instanceof ReachCapabilityBeanScanner
                || bean instanceof ReachAiRegistryClient
                || bean instanceof ReachAiRegistryProperties
                || bean instanceof ReachAiRegistryAutoConfiguration
                || bean instanceof ReachCapabilityInvoker;
    }

    private static class Handler {
        private final Object bean;
        private final Method method;
        private final ReachCapability capability;
        private final String[] parameterNames;
        private final Class<?>[] parameterTypes;

        Handler(Object bean, Method method, ReachCapability capability,
                String[] parameterNames, Class<?>[] parameterTypes) {
            this.bean = bean;
            this.method = method;
            this.capability = capability;
            this.parameterNames = parameterNames;
            this.parameterTypes = parameterTypes;
        }
    }
}
