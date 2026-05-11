package com.enterprise.ai.spring.registry;

import com.enterprise.ai.skill.AiCapability;
import com.enterprise.ai.skill.AiParam;
import org.springframework.core.MethodParameter;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class EafCapabilityScanner {

    private final RequestMappingHandlerMapping mapping;

    private final EafRegistryProperties properties;

    private final RuntimeCapabilityMetadataResolver metadataResolver;

    private final ReflectiveRequestBodySchemaBuilder bodySchemaBuilder;

    public EafCapabilityScanner(RequestMappingHandlerMapping mapping,
                                EafRegistryProperties properties,
                                RuntimeCapabilityMetadataResolver metadataResolver) {
        this.mapping = mapping;
        this.properties = properties;
        this.metadataResolver = metadataResolver;
        this.bodySchemaBuilder = new ReflectiveRequestBodySchemaBuilder(metadataResolver);
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

    private static boolean shouldSkipSpringParameter(MethodParameter p) {
        Class<?> t = p.getParameterType();
        if (t.isPrimitive()) {
            return false;
        }
        String n = t.getName();
        if (n.startsWith("javax.servlet.") || n.startsWith("jakarta.servlet.")) {
            return true;
        }
        if (WebDataBinder.class.isAssignableFrom(t) || Errors.class.isAssignableFrom(t)) {
            return true;
        }
        if (Model.class.isAssignableFrom(t)) {
            return true;
        }
        if (Principal.class.isAssignableFrom(t) || Locale.class.equals(t) || TimeZone.class.equals(t)) {
            return true;
        }
        if (java.io.InputStream.class.isAssignableFrom(t)
                || java.io.OutputStream.class.isAssignableFrom(t)
                || java.io.Reader.class.isAssignableFrom(t)
                || java.io.Writer.class.isAssignableFrom(t)) {
            return true;
        }
        if (n.startsWith("org.springframework.web.servlet.") && n.endsWith("RequestDataBinder")) {
            return true;
        }
        return n.startsWith("org.springframework.web.context.request.NativeWebRequest")
                || n.startsWith("org.springframework.web.context.request.WebRequest");
    }

    private List<EafCapabilityParameter> parameters(HandlerMethod handler, Method javaMethod) {
        List<EafCapabilityParameter> params = new ArrayList<>();
        for (MethodParameter p : handler.getMethodParameters()) {
            if (shouldSkipSpringParameter(p)) {
                continue;
            }
            if (p.hasParameterAnnotation(RequestBody.class)) {
                params.add(buildRequestBodyParameter(javaMethod, p));
                continue;
            }
            if (p.hasParameterAnnotation(PathVariable.class)) {
                params.add(buildPathVariableParameter(javaMethod, p));
                continue;
            }
            if (p.hasParameterAnnotation(RequestParam.class)) {
                params.add(buildRequestParamParameter(javaMethod, p));
                continue;
            }
            if (isLikelyImplicitRequestParam(p)) {
                params.add(buildImplicitQueryParameter(javaMethod, p));
            }
        }
        return params;
    }

    private static boolean isLikelyImplicitRequestParam(MethodParameter p) {
        Class<?> t = p.getParameterType();
        return t.equals(String.class)
                || t.equals(Integer.class) || t.equals(int.class)
                || t.equals(Long.class) || t.equals(long.class)
                || t.equals(Boolean.class) || t.equals(boolean.class)
                || t.equals(Double.class) || t.equals(double.class);
    }

    private EafCapabilityParameter buildRequestBodyParameter(Method javaMethod, MethodParameter p) {
        AiParam aiParam = p.getParameterAnnotation(AiParam.class);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (aiParam != null) {
            metadata.put("example", aiParam.example());
            metadata.put("sourceHint", aiParam.sourceHint());
            metadata.put("dictType", aiParam.dictType());
            metadata.put("sensitive", aiParam.sensitive());
        }
        RequestBody rb = p.getParameterAnnotation(RequestBody.class);
        boolean required = rb == null || rb.required();
        if (aiParam != null && aiParam.required()) {
            required = true;
        }
        String desc = metadataResolver.resolveParameterDescription(javaMethod, p, aiParam);
        if (desc == null || desc.isBlank()) {
            desc = "JSON 请求体，对应 " + p.getParameterType().getTypeName();
        }
        List<EafCapabilityParameter> children = bodySchemaBuilder.expand(p.getParameterType());
        return new EafCapabilityParameter(
                "body_json",
                "json",
                desc,
                required,
                "BODY",
                children,
                metadata.isEmpty() ? null : metadata
        );
    }

    private EafCapabilityParameter buildPathVariableParameter(Method javaMethod, MethodParameter p) {
        AiParam aiParam = p.getParameterAnnotation(AiParam.class);
        Map<String, Object> metadata = aiParamMetadata(aiParam);
        PathVariable pv = p.getParameterAnnotation(PathVariable.class);
        String name = pathVariableName(pv, p.getParameterName());
        String desc = metadataResolver.resolveParameterDescription(javaMethod, p, aiParam);
        boolean required = (aiParam != null && aiParam.required()) || (pv != null && pv.required());
        return new EafCapabilityParameter(
                name,
                p.getParameterType().getTypeName(),
                desc,
                required,
                "PATH",
                List.of(),
                metadata == null || metadata.isEmpty() ? null : metadata
        );
    }

    private EafCapabilityParameter buildRequestParamParameter(Method javaMethod, MethodParameter p) {
        AiParam aiParam = p.getParameterAnnotation(AiParam.class);
        Map<String, Object> metadata = aiParamMetadata(aiParam);
        RequestParam rp = p.getParameterAnnotation(RequestParam.class);
        String name = requestParamName(rp, p.getParameterName());
        String desc = metadataResolver.resolveParameterDescription(javaMethod, p, aiParam);
        boolean required = effectiveRequestParamRequired(rp);
        if (aiParam != null && aiParam.required()) {
            required = true;
        }
        return new EafCapabilityParameter(
                name,
                p.getParameterType().getTypeName(),
                desc,
                required,
                "QUERY",
                List.of(),
                metadata == null || metadata.isEmpty() ? null : metadata
        );
    }

    private EafCapabilityParameter buildImplicitQueryParameter(Method javaMethod, MethodParameter p) {
        AiParam aiParam = p.getParameterAnnotation(AiParam.class);
        Map<String, Object> metadata = aiParamMetadata(aiParam);
        String name = p.getParameterName() != null ? p.getParameterName() : "arg";
        String desc = metadataResolver.resolveParameterDescription(javaMethod, p, aiParam);
        boolean required = aiParam != null && aiParam.required();
        return new EafCapabilityParameter(
                name,
                p.getParameterType().getTypeName(),
                desc,
                required,
                "QUERY",
                List.of(),
                metadata == null || metadata.isEmpty() ? null : metadata
        );
    }

    private static Map<String, Object> aiParamMetadata(AiParam aiParam) {
        if (aiParam == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("example", aiParam.example());
        metadata.put("sourceHint", aiParam.sourceHint());
        metadata.put("dictType", aiParam.dictType());
        metadata.put("sensitive", aiParam.sensitive());
        return metadata;
    }

    private static String pathVariableName(PathVariable pv, String parameterName) {
        if (pv != null) {
            if (!pv.name().isBlank()) {
                return pv.name();
            }
            if (!pv.value().isBlank()) {
                return pv.value();
            }
        }
        return parameterName != null ? parameterName : "pathVar";
    }

    private static String requestParamName(RequestParam rp, String parameterName) {
        if (rp != null) {
            if (!rp.name().isBlank()) {
                return rp.name();
            }
            if (!rp.value().isBlank()) {
                return rp.value();
            }
        }
        return parameterName != null ? parameterName : "param";
    }

    /**
     * 与 Spring MVC 一致：带 {@code defaultValue} 的 {@code @RequestParam} 视为非必填。
     */
    private static boolean effectiveRequestParamRequired(RequestParam rp) {
        if (rp == null) {
            return true;
        }
        if (StringUtils.hasText(rp.defaultValue())) {
            return false;
        }
        return rp.required();
    }
}
