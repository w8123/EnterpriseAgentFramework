package com.enterprise.ai.spring.registry;

import com.enterprise.ai.skill.AiCapability;
import com.enterprise.ai.skill.AiParam;
import com.enterprise.ai.skill.annotation.AiTool;
import org.springframework.core.MethodParameter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 无 Javadoc，仅根据平台配置顺序尝试运行时注解（Swagger / OpenAPI）与方法名兜底。
 */
public class RuntimeCapabilityMetadataResolver {

    private static final int MAX_OPERATION_TEXT = 2000;

    private final SdkDescriptionSourceSettingsHolder holder;

    public RuntimeCapabilityMetadataResolver(SdkDescriptionSourceSettingsHolder holder) {
        this.holder = holder;
    }

    public String resolveMethodDescription(Method method, AiCapability cap) {
        if (cap != null && StringUtils.hasText(cap.description())) {
            return cap.description().trim();
        }
        return resolveMethodDescriptionFallback(method);
    }

    public String resolveMethodDescription(Method method, AiTool tool) {
        if (tool != null && StringUtils.hasText(tool.description())) {
            return tool.description().trim();
        }
        return resolveMethodDescriptionFallback(method);
    }

    private String resolveMethodDescriptionFallback(Method method) {
        for (String key : effectiveDescOrder()) {
            switch (normalizeKey(key)) {
                case "SWAGGER_API_OPERATION" -> {
                    Optional<String> t = swaggerApiOperationValue(method);
                    if (t.isPresent()) {
                        return t.get();
                    }
                }
                case "OPENAPI_OPERATION" -> {
                    Optional<String> t = openApiOperationText(method);
                    if (t.isPresent()) {
                        return t.get();
                    }
                }
                case "METHOD_NAME" -> {
                    return method.getName();
                }
                default -> {
                }
            }
        }
        return method.getName();
    }

    public String resolveMethodTitle(Method method, AiCapability cap) {
        if (cap != null && StringUtils.hasText(cap.title())) {
            return cap.title().trim();
        }
        return resolveMethodTitleFallback(method);
    }

    public String resolveMethodTitle(Method method, AiTool tool) {
        if (tool != null && StringUtils.hasText(tool.title())) {
            return tool.title().trim();
        }
        return resolveMethodTitleFallback(method);
    }

    private String resolveMethodTitleFallback(Method method) {
        Optional<String> sum = openApiSummary(method);
        if (sum.isPresent()) {
            return sum.get();
        }
        return swaggerApiValue(method).orElse("");
    }

    public String resolveParameterDescription(Method method, MethodParameter mp, AiParam aiParam) {
        if (aiParam != null && StringUtils.hasText(aiParam.description())) {
            return aiParam.description().trim();
        }
        for (String key : effectiveParamOrder()) {
            switch (normalizeKey(key)) {
                case "SCHEMA_ANNO" -> {
                    Optional<String> s = schemaOnParameter(mp);
                    if (s.isPresent()) {
                        return s.get();
                    }
                }
                case "PARAMETER_ANNO" -> {
                    Optional<String> p = openApiParameterDescription(mp);
                    if (p.isPresent()) {
                        return p.get();
                    }
                }
                case "FIELD_NAME" -> {
                    String n = mp.getParameterName();
                    if (StringUtils.hasText(n)) {
                        return n;
                    }
                }
                default -> {
                }
            }
        }
        String n = mp.getParameterName();
        return n != null ? n : "";
    }

    /**
     * 解析 DTO 字段、Record 组件等与形参相同的「参数说明来源」顺序（无 Javadoc 源）。
     */
    public String resolveMemberDescription(AnnotatedElement element, String fallbackName) {
        AiParam aiParam = element.getAnnotation(AiParam.class);
        if (aiParam != null && StringUtils.hasText(aiParam.description())) {
            return aiParam.description().trim();
        }
        for (String key : effectiveParamOrder()) {
            switch (normalizeKey(key)) {
                case "SCHEMA_ANNO" -> {
                    Optional<String> s = schemaOnAnnotatedElement(element);
                    if (s.isPresent()) {
                        return s.get();
                    }
                }
                case "PARAMETER_ANNO" -> {
                    Optional<String> p = parameterDescriptionOnAnnotatedElement(element);
                    if (p.isPresent()) {
                        return p.get();
                    }
                }
                case "FIELD_NAME" -> {
                    if (StringUtils.hasText(fallbackName)) {
                        return fallbackName;
                    }
                }
                default -> {
                }
            }
        }
        return fallbackName != null ? fallbackName : "";
    }

    private static Optional<String> schemaOnAnnotatedElement(AnnotatedElement element) {
        for (Annotation a : element.getAnnotations()) {
            if ("Schema".equals(a.annotationType().getSimpleName())) {
                Optional<String> d = invokeStringAttr(a, "description");
                if (d.isPresent()) {
                    return d;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> parameterDescriptionOnAnnotatedElement(AnnotatedElement element) {
        for (Annotation a : element.getAnnotations()) {
            if ("Parameter".equals(a.annotationType().getSimpleName())) {
                Optional<String> d = invokeStringAttr(a, "description");
                if (d.isPresent()) {
                    return d;
                }
                return invokeStringAttr(a, "name");
            }
        }
        for (Annotation a : element.getAnnotations()) {
            if ("ApiModelProperty".equals(a.annotationType().getSimpleName())) {
                Optional<String> v = invokeStringAttr(a, "value");
                if (v.isPresent()) {
                    return v;
                }
                return invokeStringAttr(a, "notes");
            }
        }
        return Optional.empty();
    }

    private List<String> effectiveDescOrder() {
        SdkCapabilityDescriptionSettings s = holder.current();
        return applySourceEnabled(s.descriptionSourceOrder(), s.descriptionSourceEnabled());
    }

    private List<String> effectiveParamOrder() {
        SdkCapabilityDescriptionSettings s = holder.current();
        return applySourceEnabled(s.paramDescriptionSourceOrder(), s.paramDescriptionSourceEnabled());
    }

    private static List<String> applySourceEnabled(List<String> order, Map<String, Boolean> enabled) {
        if (order == null || order.isEmpty()) {
            return List.of();
        }
        if (enabled == null || enabled.isEmpty()) {
            return new ArrayList<>(order);
        }
        List<String> out = new ArrayList<>();
        for (String k : order) {
            if (k == null) {
                continue;
            }
            String t = k.trim();
            if (Boolean.FALSE.equals(enabled.get(t))) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }

    private static Optional<String> swaggerApiOperationValue(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (!"ApiOperation".equals(a.annotationType().getSimpleName())) {
                continue;
            }
            Optional<String> v = invokeStringAttr(a, "value");
            Optional<String> notes = invokeStringAttr(a, "notes");
            return combineSummaryDesc(v, notes);
        }
        return Optional.empty();
    }

    private static Optional<String> swaggerApiValue(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (!"ApiOperation".equals(a.annotationType().getSimpleName())) {
                continue;
            }
            return invokeStringAttr(a, "value");
        }
        return Optional.empty();
    }

    private static Optional<String> openApiOperationText(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (!"Operation".equals(a.annotationType().getSimpleName())) {
                continue;
            }
            Optional<String> sum = invokeStringAttr(a, "summary");
            Optional<String> desc = invokeStringAttr(a, "description");
            return combineSummaryDesc(sum, desc).map(RuntimeCapabilityMetadataResolver::truncate);
        }
        return Optional.empty();
    }

    private static Optional<String> openApiSummary(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (!"Operation".equals(a.annotationType().getSimpleName())) {
                continue;
            }
            return invokeStringAttr(a, "summary");
        }
        return Optional.empty();
    }

    private static Optional<String> schemaOnParameter(MethodParameter mp) {
        for (Annotation a : mp.getParameterAnnotations()) {
            if ("Schema".equals(a.annotationType().getSimpleName())) {
                Optional<String> d = invokeStringAttr(a, "description");
                if (d.isPresent()) {
                    return d;
                }
            }
        }
        if (mp.hasParameterAnnotation(RequestBody.class)) {
            Class<?> t = mp.getParameterType();
            if (t != null) {
                for (Annotation a : t.getAnnotations()) {
                    if ("Schema".equals(a.annotationType().getSimpleName())) {
                        Optional<String> d = invokeStringAttr(a, "description");
                        if (d.isPresent()) {
                            return d;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> openApiParameterDescription(MethodParameter mp) {
        for (Annotation a : mp.getParameterAnnotations()) {
            if ("Parameter".equals(a.annotationType().getSimpleName())) {
                Optional<String> d = invokeStringAttr(a, "description");
                if (d.isPresent()) {
                    return d;
                }
                return invokeStringAttr(a, "name");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> invokeStringAttr(Annotation ann, String attr) {
        try {
            Method m = ann.annotationType().getMethod(attr);
            m.setAccessible(true);
            Object v = m.invoke(ann);
            if (v == null) {
                return Optional.empty();
            }
            String s = v.toString().trim();
            return s.isBlank() ? Optional.empty() : Optional.of(s);
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> combineSummaryDesc(Optional<String> a, Optional<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return Optional.empty();
        }
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        if (a.get().equals(b.get())) {
            return a;
        }
        return Optional.of(a.get() + "\n" + b.get());
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_OPERATION_TEXT) {
            return text;
        }
        return text.substring(0, MAX_OPERATION_TEXT) + "...";
    }
}
