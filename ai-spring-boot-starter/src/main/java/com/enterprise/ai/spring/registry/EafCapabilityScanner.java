package com.enterprise.ai.spring.registry;

import com.enterprise.ai.skill.AiCapability;
import com.enterprise.ai.skill.AiParam;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EafCapabilityScanner {

    private final RequestMappingHandlerMapping mapping;

    private final EafRegistryProperties properties;

    private final RuntimeCapabilityMetadataResolver metadataResolver;

    public EafCapabilityScanner(RequestMappingHandlerMapping mapping,
                                EafRegistryProperties properties,
                                RuntimeCapabilityMetadataResolver metadataResolver) {
        this.mapping = mapping;
        this.properties = properties;
        this.metadataResolver = metadataResolver;
    }

    public List<EafCapabilityDescriptor> scan() {
        if (!properties.getCapability().isScanController()) {
            return List.of();
        }
        List<EafCapabilityDescriptor> out = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (isSpringInternal(handler)) {
                return;
            }
            String path = firstPath(info);
            if (path == null) {
                return;
            }
            String method = firstMethod(info);
            Method javaMethod = handler.getMethod();
            AiCapability capability = javaMethod.getAnnotation(AiCapability.class);
            String name = capability != null && !capability.name().isBlank()
                    ? capability.name()
                    : javaMethod.getName();
            String description = metadataResolver.resolveMethodDescription(javaMethod, capability);
            String title = metadataResolver.resolveMethodTitle(javaMethod, capability);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (capability != null) {
                metadata.put("declared", true);
                metadata.put("title", capability.title());
                metadata.put("domain", capability.domain());
                metadata.put("module", capability.module());
                metadata.put("tags", capability.tags());
                metadata.put("requiredRoles", capability.requiredRoles());
                metadata.put("timeoutMs", capability.timeoutMs());
                metadata.put("retryLimit", capability.retryLimit());
                metadata.put("source", "AiCapability");
            }
            metadata.put("controllerClass", handler.getBeanType().getName());
            metadata.put("controllerSimpleName", handler.getBeanType().getSimpleName());
            out.add(new EafCapabilityDescriptor(
                    name,
                    title,
                    description,
                    method,
                    properties.getProject().getBaseUrl(),
                    properties.getProject().getContextPath(),
                    path,
                    requestBodyType(handler),
                    javaMethod.getReturnType().getTypeName(),
                    capability == null ? "WRITE" : capability.sideEffect().name(),
                    true,
                    capability == null || capability.agentVisible(),
                    false,
                    properties.getProject().getVisibility(),
                    parameters(handler, javaMethod),
                    metadata
            ));
        });
        return out;
    }

    private boolean isSpringInternal(HandlerMethod handler) {
        Package p = handler.getBeanType().getPackage();
        return p != null && p.getName().startsWith("org.springframework");
    }

    private String firstPath(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null && !info.getPathPatternsCondition().getPatterns().isEmpty()) {
            return info.getPathPatternsCondition().getPatterns().iterator().next().getPatternString();
        }
        if (info.getPatternsCondition() != null && !info.getPatternsCondition().getPatterns().isEmpty()) {
            return info.getPatternsCondition().getPatterns().iterator().next();
        }
        return null;
    }

    private String firstMethod(RequestMappingInfo info) {
        if (!info.getMethodsCondition().getMethods().isEmpty()) {
            return info.getMethodsCondition().getMethods().iterator().next().name();
        }
        RequestMapping ann = info.getClass().getAnnotation(RequestMapping.class);
        return ann == null || ann.method().length == 0 ? "POST" : ann.method()[0].name();
    }

    private String requestBodyType(HandlerMethod handler) {
        for (MethodParameter p : handler.getMethodParameters()) {
            if (p.hasParameterAnnotation(RequestBody.class)) {
                return p.getParameterType().getTypeName();
            }
        }
        return null;
    }

    private List<EafCapabilityParameter> parameters(HandlerMethod handler, Method javaMethod) {
        List<EafCapabilityParameter> params = new ArrayList<>();
        for (MethodParameter p : handler.getMethodParameters()) {
            AiParam aiParam = p.getParameterAnnotation(AiParam.class);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (aiParam != null) {
                metadata.put("example", aiParam.example());
                metadata.put("sourceHint", aiParam.sourceHint());
                metadata.put("dictType", aiParam.dictType());
                metadata.put("sensitive", aiParam.sensitive());
            }
            String desc = metadataResolver.resolveParameterDescription(javaMethod, p, aiParam);
            params.add(new EafCapabilityParameter(
                    p.getParameterName(),
                    p.getParameterType().getTypeName(),
                    desc,
                    aiParam != null && aiParam.required(),
                    p.hasParameterAnnotation(RequestBody.class) ? "BODY" : "QUERY",
                    List.of(),
                    metadata
            ));
        }
        return params;
    }
}
