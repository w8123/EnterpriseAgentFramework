package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiMappingCondition;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiParameter;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiRequestBody;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiResponse;
import org.springframework.http.HttpEntity;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.servlet.mvc.condition.MediaTypeExpression;
import org.springframework.web.servlet.mvc.condition.NameValueExpression;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Converts actual Spring MVC mapping semantics into HTTP API source evidence.
 *
 * <p>It intentionally does not manufacture {@code ReachCapabilityDescriptor}s. Explicit
 * {@code @ReachCapability} declarations are scanned by the business-method scanner separately;
 * a dual-declared Java method therefore produces two different source facts.</p>
 */
final class ReachSpringMvcEndpointScanner {

    private ReachSpringMvcEndpointScanner() {
    }

    static List<ReachHttpApiDescriptor> scanClass(Class<?> type) {
        if (type == null || !AnnotatedElementUtils.hasAnnotation(type, RestController.class)) {
            return Collections.emptyList();
        }
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        RequestMappingInfo classInfo = mappingInfo(classMapping);
        List<String> classPaths = paths(classMapping);
        List<ReachHttpApiDescriptor> descriptors = new ArrayList<ReachHttpApiDescriptor>();
        for (Method method : type.getDeclaredMethods()) {
            RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (methodMapping == null) {
                continue;
            }
            // Delegate class/method combination to Spring itself: request methods are unioned while
            // consumes/produces use their own override semantics and Content-Type/Accept headers.
            RequestMappingInfo mapping = classInfo.combine(mappingInfo(methodMapping));
            List<String> methodPaths = paths(methodMapping);
            List<RequestMethod> methods = effectiveMethods(mapping);
            List<String> consumes = mediaTypes(mapping.getConsumesCondition().getExpressions());
            List<String> produces = mediaTypes(mapping.getProducesCondition().getExpressions());
            List<ReachHttpApiMappingCondition> conditions = mappingConditions(mapping);
            ParameterFacts parameterFacts = parameters(method, consumes);
            for (String classPath : classPaths) {
                for (String methodPath : methodPaths) {
                    String endpointPath = combinePath(classPath, methodPath);
                    for (RequestMethod httpMethod : methods) {
                        descriptors.add(descriptor(type, method, httpMethod.name(), endpointPath, consumes, produces,
                                conditions, parameterFacts));
                    }
                }
            }
        }
        Collections.sort(descriptors, new Comparator<ReachHttpApiDescriptor>() {
            @Override
            public int compare(ReachHttpApiDescriptor left, ReachHttpApiDescriptor right) {
                return left.getSourceKey().compareTo(right.getSourceKey());
            }
        });
        return descriptors;
    }

    private static ReachHttpApiDescriptor descriptor(Class<?> type,
                                                       Method method,
                                                       String httpMethod,
                                                       String endpointPath,
                                                       List<String> consumes,
                                                       List<String> produces,
                                                       List<ReachHttpApiMappingCondition> conditions,
                                                       ParameterFacts parameterFacts) {
        ReachHttpApiDescriptor descriptor = new ReachHttpApiDescriptor();
        descriptor.setHttpMethod(httpMethod);
        descriptor.setEndpointPath(endpointPath);
        descriptor.setConsumes(consumes);
        descriptor.setProduces(produces);
        descriptor.setMappingConditions(copyConditions(conditions));
        descriptor.setParameters(parameterFacts.parameters);
        descriptor.setRequestBody(parameterFacts.requestBody);
        ReachHttpApiResponse response = new ReachHttpApiResponse();
        // DEFAULT means unknown/dynamic, not an invented 200. Explicit Spring status stays factual.
        response.setStatus(responseStatus(type, method));
        response.setSchema(schema(responseBodyType(method.getGenericReturnType())));
        response.setContentTypes(produces);
        descriptor.setResponses(Collections.singletonList(response));
        descriptor.setAuthenticationState("UNKNOWN");
        descriptor.setSideEffect(readOnlyMethod(httpMethod) ? "READ_ONLY" : "WRITE");
        String sourceLocation = sourceLocation(type, method);
        descriptor.setSourceLocation(sourceLocation);
        descriptor.setSourceKey("mvc:" + sha256(sourceLocation + "|" + httpMethod + "|" + endpointPath + "|"
                + conditionKey(conditions) + "|" + mediaKey(consumes) + "|" + mediaKey(produces)));
        return descriptor;
    }

    private static ParameterFacts parameters(Method method, List<String> consumes) {
        List<ReachHttpApiParameter> parameters = new ArrayList<ReachHttpApiParameter>();
        ReachHttpApiRequestBody requestBody = null;
        Parameter[] declared = method.getParameters();
        for (int index = 0; index < declared.length; index++) {
            Parameter parameter = declared[index];
            PathVariable path = parameter.getAnnotation(PathVariable.class);
            RequestParam query = parameter.getAnnotation(RequestParam.class);
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            CookieValue cookie = parameter.getAnnotation(CookieValue.class);
            RequestBody body = parameter.getAnnotation(RequestBody.class);
            int annotations = count(path, query, header, cookie, body);
            if (annotations == 0) {
                throw new IllegalArgumentException("Cannot determine Spring MVC parameter binding location for "
                        + method.toGenericString() + " parameter " + index);
            }
            if (annotations > 1) {
                throw new IllegalArgumentException("Spring MVC parameter has multiple binding annotations: "
                        + method.toGenericString() + " parameter " + index);
            }
            if (body != null) {
                if (requestBody != null) {
                    throw new IllegalArgumentException("Spring MVC operation has multiple request bodies: "
                            + method.toGenericString());
                }
                requestBody = new ReachHttpApiRequestBody();
                requestBody.setRequired(body.required() && !optionalType(parameter.getParameterizedType()));
                requestBody.setSchema(schema(parameter.getParameterizedType()));
                requestBody.setContentTypes(consumes);
                continue;
            }
            ReachHttpApiParameter item = new ReachHttpApiParameter();
            item.setSchema(schema(parameter.getParameterizedType()));
            if (path != null) {
                item.setLocation("PATH");
                item.setName(annotationName(path.name(), path.value(), parameter, index));
                item.setRequired(required(path.required(), null, parameter.getParameterizedType()));
            } else if (query != null) {
                item.setLocation("QUERY");
                item.setName(annotationName(query.name(), query.value(), parameter, index));
                item.setRequired(required(query.required(), query.defaultValue(), parameter.getParameterizedType()));
            } else if (header != null) {
                item.setLocation("HEADER");
                item.setName(annotationName(header.name(), header.value(), parameter, index));
                item.setRequired(required(header.required(), header.defaultValue(), parameter.getParameterizedType()));
            } else {
                item.setLocation("COOKIE");
                item.setName(annotationName(cookie.name(), cookie.value(), parameter, index));
                item.setRequired(required(cookie.required(), cookie.defaultValue(), parameter.getParameterizedType()));
            }
            parameters.add(item);
        }
        return new ParameterFacts(parameters, requestBody);
    }

    private static int count(Object... values) {
        int count = 0;
        for (Object value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    private static RequestMappingInfo mappingInfo(RequestMapping mapping) {
        List<String> mappingPaths = paths(mapping);
        RequestMappingInfo.Builder builder = RequestMappingInfo.paths(mappingPaths.toArray(new String[0]));
        if (mapping == null) {
            return builder.build();
        }
        return builder.methods(mapping.method())
                .params(mapping.params())
                .headers(mapping.headers())
                .consumes(mapping.consumes())
                .produces(mapping.produces())
                .build();
    }

    private static List<RequestMethod> effectiveMethods(RequestMappingInfo mapping) {
        Set<RequestMethod> declared = mapping.getMethodsCondition().getMethods();
        Set<RequestMethod> values = declared.isEmpty()
                ? new LinkedHashSet<RequestMethod>(java.util.Arrays.asList(RequestMethod.values()))
                : new LinkedHashSet<RequestMethod>(declared);
        List<RequestMethod> result = new ArrayList<RequestMethod>(values);
        Collections.sort(result, new Comparator<RequestMethod>() {
            @Override
            public int compare(RequestMethod left, RequestMethod right) {
                return left.name().compareTo(right.name());
            }
        });
        return result;
    }

    private static List<String> mediaTypes(Set<MediaTypeExpression> expressions) {
        Set<String> values = new LinkedHashSet<String>();
        for (MediaTypeExpression expression : expressions) {
            String value = expression.getMediaType().toString().toLowerCase(Locale.ROOT);
            values.add(expression.isNegated() ? "!" + value : value);
        }
        List<String> result = new ArrayList<String>(values);
        Collections.sort(result);
        return result;
    }

    private static List<ReachHttpApiMappingCondition> mappingConditions(RequestMappingInfo mapping) {
        List<ReachHttpApiMappingCondition> result = new ArrayList<ReachHttpApiMappingCondition>();
        addConditions(result, "PARAM", mapping.getParamsCondition().getExpressions());
        // HeadersRequestCondition has already moved Content-Type and Accept into the media conditions.
        addConditions(result, "HEADER", mapping.getHeadersCondition().getExpressions());
        Collections.sort(result, new Comparator<ReachHttpApiMappingCondition>() {
            @Override
            public int compare(ReachHttpApiMappingCondition left, ReachHttpApiMappingCondition right) {
                int kind = conditionPart(left.getKind()).compareTo(conditionPart(right.getKind()));
                if (kind != 0) return kind;
                int name = conditionPart(left.getName()).compareTo(conditionPart(right.getName()));
                if (name != 0) return name;
                int operator = conditionPart(left.getOperator()).compareTo(conditionPart(right.getOperator()));
                return operator != 0 ? operator : conditionPart(left.getValue()).compareTo(conditionPart(right.getValue()));
            }
        });
        return result;
    }

    private static void addConditions(List<ReachHttpApiMappingCondition> target, String kind,
                                      Set<NameValueExpression<String>> expressions) {
        for (NameValueExpression<String> expression : expressions) {
            ReachHttpApiMappingCondition condition = new ReachHttpApiMappingCondition();
            condition.setKind(kind);
            condition.setName(expression.getName());
            if (expression.getValue() == null) {
                condition.setOperator(expression.isNegated() ? "ABSENT" : "PRESENT");
            } else {
                condition.setOperator(expression.isNegated() ? "NOT_EQUALS" : "EQUALS");
                condition.setValue(expression.getValue());
            }
            if (!StringUtils.hasText(condition.getName())) {
                throw new IllegalArgumentException("Spring MVC mapping condition name is blank");
            }
            target.add(condition);
        }
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) {
            return Collections.singletonList("");
        }
        List<String> paths = normalizedPaths(mapping.path());
        if (paths.isEmpty()) {
            paths = normalizedPaths(mapping.value());
        }
        return paths.isEmpty() ? Collections.singletonList("") : paths;
    }

    private static List<String> normalizedPaths(String[] values) {
        if (values == null || values.length == 0) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<String>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                unique.add(value.trim());
            }
        }
        List<String> result = new ArrayList<String>(unique);
        Collections.sort(result);
        return result;
    }

    private static String annotationName(String name, String value, Parameter parameter, int index) {
        if (Map.class.isAssignableFrom(rawType(unwrapOptional(parameter.getParameterizedType())))
                && !StringUtils.hasText(name) && !StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Cannot represent unnamed Spring MVC map capture for "
                    + parameter.getDeclaringExecutable().toGenericString() + " parameter " + index);
        }
        return firstText(name, value, parameter.isNamePresent() ? parameter.getName() : null, "arg" + index);
    }

    private static String combinePath(String left, String right) {
        String a = normalizePath(left);
        String b = normalizePath(right);
        if ("/".equals(a)) {
            return b;
        }
        if ("/".equals(b)) {
            return a;
        }
        return a + b;
    }

    private static String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        String out = path.trim();
        if (!out.startsWith("/")) {
            out = "/" + out;
        }
        out = out.replaceAll("/+", "/");
        while (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /**
     * Produces the same executable schema granularity as the Controller source scanner. This keeps
     * the same Spring MVC operation observed through Starter and static source intake comparable
     * without discarding DTO field facts from either source.
     */
    private static Map<String, Object> schema(Type type) {
        return schema(type, new LinkedHashSet<String>(), 0);
    }

    private static Map<String, Object> schema(Type type, Set<String> visiting, int depth) {
        Type schemaType = unwrapOptional(type);
        Class<?> raw = rawType(schemaType);
        if (raw == null || Void.TYPE.equals(raw) || Void.class.equals(raw)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (stringType(raw) || raw.isEnum()) {
            result.put("type", "string");
        } else if (integerType(raw)) {
            result.put("type", "integer");
        } else if (Boolean.class.equals(raw) || Boolean.TYPE.equals(raw)) {
            result.put("type", "boolean");
        } else if (Number.class.isAssignableFrom(raw) || raw.isPrimitive()) {
            result.put("type", "number");
        } else if (raw.isArray() || Iterable.class.isAssignableFrom(raw)) {
            result.put("type", "array");
            Type itemType = componentType(schemaType, raw);
            result.put("items", nonNullSchema(schema(itemType, visiting, depth + 1)));
        } else if (Map.class.isAssignableFrom(raw)) {
            result.put("type", "object");
            result.put("additionalProperties", nonNullSchema(schema(mapValueType(schemaType), visiting, depth + 1)));
        } else {
            result.put("type", "object");
            addObjectProperties(result, raw, visiting, depth);
        }
        return result;
    }

    private static boolean stringType(Class<?> raw) {
        String name = raw.getSimpleName().toLowerCase(Locale.ROOT);
        return "string".equals(name) || "charsequence".equals(name) || "char".equals(name)
                || "character".equals(name) || "uuid".equals(name) || "uri".equals(name)
                || "url".equals(name) || "localdate".equals(name) || "localdatetime".equals(name)
                || "localtime".equals(name) || "offsetdatetime".equals(name) || "zoneddatetime".equals(name)
                || "instant".equals(name) || "date".equals(name) || "timestamp".equals(name);
    }

    private static boolean integerType(Class<?> raw) {
        String name = raw.getSimpleName().toLowerCase(Locale.ROOT);
        return "byte".equals(name) || "short".equals(name) || "int".equals(name) || "integer".equals(name)
                || "long".equals(name) || "biginteger".equals(name);
    }

    private static void addObjectProperties(Map<String, Object> target,
                                            Class<?> raw,
                                            Set<String> visiting,
                                            int depth) {
        if (depth >= 6 || !projectObjectType(raw)) {
            return;
        }
        String key = raw.getName();
        if (!visiting.add(key)) {
            return;
        }
        try {
            Map<String, Object> properties = new TreeMap<String, Object>();
            Set<String> required = new TreeSet<String>();
            for (Field field : raw.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                        || hasAnnotation(field, "JsonIgnore")) {
                    continue;
                }
                String property = jsonName(field, field.getName());
                properties.put(property, nonNullSchema(schema(field.getGenericType(), visiting, depth + 1)));
                if (required(field)) {
                    required.add(property);
                }
            }
            if (!properties.isEmpty()) {
                target.put("properties", properties);
            }
            if (!required.isEmpty()) {
                target.put("required", new ArrayList<String>(required));
            }
        } finally {
            visiting.remove(key);
        }
    }

    private static boolean projectObjectType(Class<?> raw) {
        Package packageInfo = raw.getPackage();
        String packageName = packageInfo == null ? "" : packageInfo.getName();
        return !raw.isInterface() && !packageName.startsWith("java.") && !packageName.startsWith("javax.")
                && !packageName.startsWith("jakarta.") && !packageName.startsWith("org.springframework.");
    }

    private static boolean hasAnnotation(Field field, String simpleName) {
        for (Annotation annotation : field.getAnnotations()) {
            if (simpleName.equals(annotation.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static String jsonName(Field field, String fallback) {
        for (Annotation annotation : field.getAnnotations()) {
            if ("JsonProperty".equals(annotation.annotationType().getSimpleName())) {
                Object value = annotationValue(annotation, "value");
                if (value instanceof String && StringUtils.hasText((String) value)) {
                    return ((String) value).trim();
                }
            }
        }
        return fallback;
    }

    private static boolean required(Field field) {
        for (Annotation annotation : field.getAnnotations()) {
            String name = annotation.annotationType().getSimpleName();
            if ("NotNull".equals(name) || "NotBlank".equals(name) || "NotEmpty".equals(name)) {
                return true;
            }
            if ("Schema".equals(name) || "JsonProperty".equals(name)) {
                if (Boolean.TRUE.equals(annotationValue(annotation, "required"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object annotationValue(Annotation annotation, String property) {
        try {
            return annotation.annotationType().getMethod(property).invoke(annotation);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Object> nonNullSchema(Map<String, Object> schema) {
        return schema == null ? Collections.<String, Object>singletonMap("type", "object") : schema;
    }

    private static String responseStatus(Class<?> type, Method method) {
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(method, ResponseStatus.class);
        if (status == null) {
            status = AnnotatedElementUtils.findMergedAnnotation(type, ResponseStatus.class);
        }
        return status == null ? "DEFAULT" : Integer.toString(status.code().value());
    }

    private static boolean required(boolean declaredRequired, String defaultValue, Type type) {
        return declaredRequired && !hasDefaultValue(defaultValue) && !optionalType(type);
    }

    private static boolean hasDefaultValue(String value) {
        return value != null && !ValueConstants.DEFAULT_NONE.equals(value);
    }

    private static boolean optionalType(Type type) {
        return Optional.class.equals(rawType(type));
    }

    private static Type responseBodyType(Type type) {
        Type result = type;
        while (responseWrapper(rawType(result))) {
            result = genericTypeArgument(result);
        }
        return result;
    }

    private static boolean responseWrapper(Class<?> raw) {
        if (raw == null || Object.class.equals(raw)) {
            return false;
        }
        if (Optional.class.equals(raw) || HttpEntity.class.isAssignableFrom(raw)) {
            return true;
        }
        String name = raw.getSimpleName().toLowerCase(Locale.ROOT);
        return "apiresult".equals(name) || "webapiresult".equals(name) || "apiresponse".equals(name)
                || "result".equals(name) || "responseentity".equals(name) || "response".equals(name)
                || "basresult".equals(name) || "commonresult".equals(name) || "restult".equals(name)
                || "ajaxresult".equals(name) || "jsonresult".equals(name) || "httpentity".equals(name);
    }

    private static Type unwrapOptional(Type type) {
        Type result = type;
        while (Optional.class.equals(rawType(result))) {
            result = genericTypeArgument(result);
        }
        return result;
    }

    private static Type genericTypeArgument(Type type) {
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (arguments.length == 1) {
                return arguments[0];
            }
        }
        return Object.class;
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType && ((ParameterizedType) type).getRawType() instanceof Class<?>) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        return Object.class;
    }

    private static Type componentType(Type type, Class<?> raw) {
        if (raw.isArray()) {
            if (type instanceof GenericArrayType) {
                return ((GenericArrayType) type).getGenericComponentType();
            }
            return raw.getComponentType();
        }
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (arguments.length == 1) {
                return arguments[0];
            }
        }
        return Object.class;
    }

    private static Type mapValueType(Type type) {
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (arguments.length >= 2) {
                return arguments[1];
            }
        }
        return Object.class;
    }

    private static List<ReachHttpApiMappingCondition> copyConditions(List<ReachHttpApiMappingCondition> conditions) {
        List<ReachHttpApiMappingCondition> copy = new ArrayList<ReachHttpApiMappingCondition>();
        for (ReachHttpApiMappingCondition source : conditions) {
            ReachHttpApiMappingCondition item = new ReachHttpApiMappingCondition();
            item.setKind(source.getKind());
            item.setName(source.getName());
            item.setOperator(source.getOperator());
            item.setValue(source.getValue());
            copy.add(item);
        }
        return copy;
    }

    private static String sourceLocation(Class<?> type, Method method) {
        StringBuilder value = new StringBuilder("mvc:").append(type.getName()).append('#')
                .append(method.getName()).append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                value.append(',');
            }
            value.append(parameterTypes[i].getName());
        }
        return value.append(')').toString();
    }

    private static String conditionKey(List<ReachHttpApiMappingCondition> conditions) {
        Set<String> parts = new java.util.TreeSet<String>();
        for (ReachHttpApiMappingCondition condition : conditions) {
            String name = conditionPart(condition.getName());
            if ("HEADER".equals(condition.getKind())) {
                name = name.toLowerCase(Locale.ROOT);
            }
            parts.add(conditionPart(condition.getKind()) + ':' + name + ':'
                    + conditionPart(condition.getOperator()) + ':' + conditionPart(condition.getValue()));
        }
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            key.append(part).append(';');
        }
        return key.toString();
    }

    private static String mediaKey(List<String> values) {
        StringBuilder key = new StringBuilder();
        for (String value : values) {
            key.append(value).append(';');
        }
        return key.toString();
    }

    private static String conditionPart(String value) {
        return value == null ? "" : value;
    }

    private static boolean readOnlyMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || "TRACE".equals(method);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                String text = Integer.toHexString(item & 0xff);
                if (text.length() == 1) {
                    hex.append('0');
                }
                hex.append(text);
            }
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static class ParameterFacts {
        private final List<ReachHttpApiParameter> parameters;
        private final ReachHttpApiRequestBody requestBody;

        private ParameterFacts(List<ReachHttpApiParameter> parameters, ReachHttpApiRequestBody requestBody) {
            this.parameters = parameters;
            this.requestBody = requestBody;
        }
    }
}
