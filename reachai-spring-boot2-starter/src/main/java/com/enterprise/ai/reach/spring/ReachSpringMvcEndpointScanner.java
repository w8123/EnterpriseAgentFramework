package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachHttpOperation;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiMappingCondition;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiParameter;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiRequestBody;
import com.enterprise.ai.reach.sdk.capability.ReachHttpApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.beans.BeanUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.ResolvableType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.servlet.mvc.condition.MediaTypeExpression;
import org.springframework.web.servlet.mvc.condition.NameValueExpression;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.annotation.Annotation;
import java.beans.PropertyDescriptor;
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
        return scanClass(type, Collections.<HttpMessageConverter<?>>emptyList());
    }

    static List<ReachHttpApiDescriptor> scanClass(Class<?> type, List<HttpMessageConverter<?>> converters) {
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
            inferRequestMedia(method, parameterFacts, converters);
            inferJsonRequestSchema(method, parameterFacts, converters);
            for (String classPath : classPaths) {
                for (String methodPath : methodPaths) {
                    String endpointPath = combinePath(classPath, methodPath);
                    for (RequestMethod httpMethod : methods) {
                        descriptors.add(descriptor(type, method, httpMethod.name(), endpointPath, consumes, produces,
                                conditions, parameterFacts, converters));
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
                                                       ParameterFacts parameterFacts,
                                                       List<HttpMessageConverter<?>> converters) {
        ReachHttpApiDescriptor descriptor = new ReachHttpApiDescriptor();
        descriptor.setHttpMethod(httpMethod);
        descriptor.setBusinessGroups(businessGroups(type, method));
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
        ResolvableType responseType = responseBodyType(method.getGenericReturnType());
        response.setContentTypes(produces.isEmpty() && (jsonObjectType(responseType) || jsonArrayType(responseType))
                && supportsJsonWrite(converters, responseType)
                ? Collections.singletonList("application/json") : produces);
        MediaType jsonResponseMedia = jsonMedia(response.getContentTypes());
        if (jsonResponseMedia != null) {
            response.setSchema(jsonSchema(responseType, jsonMapper(converters, responseType,
                    method.getDeclaringClass(), jsonResponseMedia, false)));
        }
        descriptor.setResponses(Collections.singletonList(response));
        descriptor.setAuthenticationState("UNKNOWN");
        ReachHttpOperation effect = AnnotatedElementUtils.findMergedAnnotation(method, ReachHttpOperation.class);
        if (effect == null) effect = AnnotatedElementUtils.findMergedAnnotation(type, ReachHttpOperation.class);
        descriptor.setSideEffect(effect == null ? readOnlyMethod(httpMethod) ? "READ_ONLY" : "WRITE"
                : effect.sideEffect() == ReachSideEffectLevel.READ ? "READ_ONLY" : effect.sideEffect().name());
        String sourceLocation = sourceLocation(type, method);
        descriptor.setSourceLocation(sourceLocation);
        descriptor.setSourceKey("mvc:" + sha256(sourceLocation + "|" + httpMethod + "|" + endpointPath + "|"
                + conditionKey(conditions) + "|" + mediaKey(consumes) + "|" + mediaKey(produces)));
        return descriptor;
    }

    /** Actual adapter converters are evidence of a supported media type, not a mapping restriction. */
    private static void inferRequestMedia(Method method, ParameterFacts facts, List<HttpMessageConverter<?>> converters) {
        if (facts.requestBody == null || !facts.requestBody.getContentTypes().isEmpty()) return;
        for (Parameter parameter : method.getParameters()) {
            ResolvableType type = ResolvableType.forType(parameter.getParameterizedType());
            if (parameter.getAnnotation(RequestBody.class) != null || HttpEntity.class.isAssignableFrom(parameter.getType())) {
                if (HttpEntity.class.isAssignableFrom(parameter.getType())) type = type.as(HttpEntity.class).getGeneric(0);
                while (Optional.class.equals(type.resolve())) type = type.getGeneric(0);
                if (String.class.equals(type.resolve())) {
                    inferStringRequestMedia(method, type, facts.requestBody, converters);
                } else if (jsonObjectType(type) || jsonArrayType(type)) {
                    for (HttpMessageConverter<?> converter : converters) {
                        // Lists must retain their actual element Type. A raw List.class check can
                        // claim support even when a generic converter rejects the declared DTOs.
                        if (!(converter instanceof GenericHttpMessageConverter) && jsonArrayType(type)) continue;
                        if (canRead(converter, type, method.getDeclaringClass(), MediaType.APPLICATION_JSON)) {
                            facts.requestBody.setContentTypes(Collections.singletonList("application/json"));
                            break;
                        }
                    }
                }
                return;
            }
        }
    }

    private static void inferStringRequestMedia(Method method, ResolvableType type, ReachHttpApiRequestBody body,
                                                List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            boolean plain = canRead(converter, type, method.getDeclaringClass(), MediaType.TEXT_PLAIN);
            boolean json = canRead(converter, type, method.getDeclaringClass(), MediaType.APPLICATION_JSON);
            if (!plain && !json) continue;
            if (plain && converter instanceof StringHttpMessageConverter) {
                body.setContentTypes(Collections.singletonList("text/plain"));
            } else if (json && converter instanceof GenericHttpMessageConverter) {
                body.setContentTypes(Collections.singletonList("application/json"));
            }
            // Preserve actual converter ordering. An unknown/custom reader is not evidence
            // that a later familiar converter will receive this string with the same semantics.
            return;
        }
    }

    private static boolean canRead(HttpMessageConverter<?> converter, ResolvableType type,
                                   Class<?> contextClass, MediaType mediaType) {
        if (converter instanceof GenericHttpMessageConverter) {
            return ((GenericHttpMessageConverter<?>) converter).canRead(type.getType(), contextClass, mediaType);
        }
        Class<?> raw = type.resolve();
        return raw != null && converter.canRead(raw, mediaType);
    }

    private static boolean jsonArrayType(ResolvableType type) {
        Class<?> raw = type.resolve();
        return raw != null && (Iterable.class.isAssignableFrom(raw)
                || raw.isArray() && !byte[].class.equals(raw) && !char[].class.equals(raw));
    }

    private static void inferJsonRequestSchema(Method method, ParameterFacts facts, List<HttpMessageConverter<?>> converters) {
        if (facts.requestBody == null) return;
        MediaType media = jsonMedia(facts.requestBody.getContentTypes());
        if (media == null) return;
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getAnnotation(RequestBody.class) == null && !HttpEntity.class.isAssignableFrom(parameter.getType())) continue;
            ResolvableType type = ResolvableType.forType(parameter.getParameterizedType());
            if (HttpEntity.class.isAssignableFrom(parameter.getType())) type = type.as(HttpEntity.class).getGeneric(0);
            while (Optional.class.equals(type.resolve())) type = type.getGeneric(0);
            facts.requestBody.setSchema(jsonSchema(type, jsonMapper(converters, type, method.getDeclaringClass(), media, true)));
            return;
        }
    }

    private static MediaType jsonMedia(List<String> contentTypes) {
        for (String value : contentTypes) {
            if (value == null || value.startsWith("!")) continue;
            try {
                MediaType media = MediaType.parseMediaType(value);
                if ("json".equalsIgnoreCase(media.getSubtype()) || media.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json")) return media;
            } catch (IllegalArgumentException ignored) { /* An unknown media is not JSON evidence. */ }
        }
        return null;
    }

    private static ObjectMapper jsonMapper(List<HttpMessageConverter<?>> converters, ResolvableType type,
                                           Class<?> contextClass, MediaType media, boolean reading) {
        Class<?> raw = type.resolve();
        if (raw == null) return null;
        for (HttpMessageConverter<?> converter : converters) {
            boolean supported = reading ? canRead(converter, type, contextClass, media)
                    : converter instanceof GenericHttpMessageConverter
                    ? ((GenericHttpMessageConverter<?>) converter).canWrite(type.getType(), raw, media)
                    : converter.canWrite(raw, media);
            if (!supported) continue;
            if (!(converter instanceof AbstractJackson2HttpMessageConverter)) return null;
            AbstractJackson2HttpMessageConverter jackson = (AbstractJackson2HttpMessageConverter) converter;
            Map<MediaType, ObjectMapper> mappings = jackson.getObjectMappersForType(raw);
            if (mappings != null && !mappings.isEmpty()) {
                for (Map.Entry<MediaType, ObjectMapper> mapping : mappings.entrySet()) {
                    if (mapping.getKey().includes(media)) return mapping.getValue();
                }
                return null;
            }
            return jackson.getObjectMapper();
        }
        return null;
    }

    private static boolean jsonObjectType(ResolvableType type) {
        while (Optional.class.equals(type.resolve())) type = type.getGeneric(0);
        Class<?> raw = type.resolve();
        return raw != null && !Object.class.equals(raw) && !Void.class.equals(raw) && !Void.TYPE.equals(raw)
                && !BeanUtils.isSimpleProperty(raw) && !raw.isArray()
                && !Iterable.class.isAssignableFrom(raw) && !assignableTo(raw, "org.springframework.core.io.Resource")
                && !assignableTo(raw, "java.io.InputStream") && !assignableTo(raw, "java.io.Reader")
                && !assignableTo(raw, "org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody")
                && !assignableTo(raw, "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter");
    }

    private static boolean supportsJsonWrite(List<HttpMessageConverter<?>> converters, ResolvableType type) {
        Class<?> raw = type.resolve();
        if (raw == null) return false;
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof GenericHttpMessageConverter) {
                if (((GenericHttpMessageConverter<?>) converter).canWrite(type.getType(), raw, MediaType.APPLICATION_JSON)) return true;
            } else if (!jsonArrayType(type) && converter.canWrite(raw, MediaType.APPLICATION_JSON)) {
                return true;
            }
        }
        return false;
    }


    /** Documentation labels are adjunct metadata and never participate in route identity. */
    private static List<String> businessGroups(Class<?> type, Method method) {
        Set<String> names = new LinkedHashSet<String>();
        for (Annotation annotation : method.getAnnotations()) collectBusinessGroups(annotation, names);
        if (names.isEmpty()) for (Annotation annotation : type.getAnnotations()) collectBusinessGroups(annotation, names);
        return new ArrayList<String>(names);
    }

    private static void collectBusinessGroups(Annotation annotation, Set<String> names) {
        String name = annotation.annotationType().getName();
        try {
            if ("io.swagger.v3.oas.annotations.tags.Tags".equals(name)) {
                for (Annotation nested : (Annotation[]) annotation.annotationType().getMethod("value").invoke(annotation)) collectBusinessGroups(nested, names);
            } else if ("io.swagger.v3.oas.annotations.tags.Tag".equals(name)) {
                businessGroup(annotation.annotationType().getMethod("name").invoke(annotation), names);
            } else if ("io.swagger.annotations.Api".equals(name) || "io.swagger.v3.oas.annotations.Operation".equals(name)) {
                for (String value : (String[]) annotation.annotationType().getMethod("tags").invoke(annotation)) businessGroup(value, names);
                if (names.isEmpty() && "io.swagger.annotations.Api".equals(name)) businessGroup(annotation.annotationType().getMethod("value").invoke(annotation), names);
            }
        } catch (ReflectiveOperationException ignored) {
            // Optional documentation must not make a callable endpoint disappear.
        }
    }

    private static void businessGroup(Object value, Set<String> names) {
        if (!(value instanceof String) || names.size() >= 30) return;
        String text = ((String) value).trim();
        if (!text.isEmpty() && text.length() <= 128 && !text.chars().anyMatch(Character::isISOControl)) names.add(text);
    }

    private static ParameterFacts parameters(Method method, List<String> consumes) {
        List<ReachHttpApiParameter> parameters = new ArrayList<ReachHttpApiParameter>();
        ReachHttpApiRequestBody requestBody = null;
        Map<String, Object> multipartProperties = new TreeMap<String, Object>();
        List<String> multipartRequired = new ArrayList<String>();
        Parameter[] declared = method.getParameters();
        for (int index = 0; index < declared.length; index++) {
            Parameter parameter = declared[index];
            PathVariable path = parameter.getAnnotation(PathVariable.class);
            RequestParam query = parameter.getAnnotation(RequestParam.class);
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            CookieValue cookie = parameter.getAnnotation(CookieValue.class);
            RequestBody body = parameter.getAnnotation(RequestBody.class);
            ModelAttribute model = parameter.getAnnotation(ModelAttribute.class);
            RequestPart part = parameter.getAnnotation(RequestPart.class);
            int annotations = count(path, query, header, cookie, body, model, part);
            if (annotations == 0) {
                if (frameworkArgument(parameter.getType())) continue;
                if (HttpEntity.class.isAssignableFrom(parameter.getType())) {
                    if (requestBody != null) throw new IllegalArgumentException("Spring MVC operation has multiple request bodies: " + method.toGenericString());
                    requestBody = new ReachHttpApiRequestBody();
                    requestBody.setRequired(false);
                    requestBody.setSchema(schema(ResolvableType.forType(parameter.getParameterizedType()).as(HttpEntity.class).getGeneric(0)));
                    requestBody.setContentTypes(consumes);
                    continue;
                }
                if (!BeanUtils.isSimpleProperty(rawType(unwrapOptional(parameter.getParameterizedType())))
                        && !multipartType(ResolvableType.forType(parameter.getParameterizedType()))) {
                    modelParameters(parameters, ResolvableType.forType(parameter.getParameterizedType()), "",
                            new LinkedHashSet<String>(), method, index);
                    continue;
                }
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
            if (model != null) {
                modelParameters(parameters, ResolvableType.forType(parameter.getParameterizedType()), "",
                        new LinkedHashSet<String>(), method, index);
                continue;
            }
            if (part != null || multipartType(ResolvableType.forType(parameter.getParameterizedType()))) {
                String name = part == null ? annotationName(query == null ? null : query.name(),
                        query == null ? null : query.value(), parameter, index)
                        : annotationName(part.name(), part.value(), parameter, index);
                multipartProperties.put(name, multipartSchema(ResolvableType.forType(parameter.getParameterizedType())));
                if (part != null ? required(part.required(), null, parameter.getParameterizedType())
                        : query == null || required(query.required(), query.defaultValue(), parameter.getParameterizedType())) {
                    multipartRequired.add(name);
                }
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
            } else if (cookie != null) {
                item.setLocation("COOKIE");
                item.setName(annotationName(cookie.name(), cookie.value(), parameter, index));
                item.setRequired(required(cookie.required(), cookie.defaultValue(), parameter.getParameterizedType()));
            } else {
                // RequestParamMethodArgumentResolver(useDefaultResolution=true) resolves simple
                // unannotated arguments as optional request parameters, never as a JSON body.
                item.setLocation("QUERY");
                item.setName(annotationName(null, null, parameter, index));
                item.setRequired(false);
            }
            parameters.add(item);
        }
        if (!multipartProperties.isEmpty()) {
            if (requestBody != null) throw new IllegalArgumentException("Cannot combine multipart and another request body: " + method.toGenericString());
            Map<String, Object> bodySchema = new LinkedHashMap<String, Object>();
            bodySchema.put("type", "object");
            bodySchema.put("properties", multipartProperties);
            if (!multipartRequired.isEmpty()) bodySchema.put("required", multipartRequired);
            requestBody = new ReachHttpApiRequestBody();
            requestBody.setRequired(!multipartRequired.isEmpty());
            requestBody.setSchema(bodySchema);
            requestBody.setContentTypes(consumes.isEmpty() ? Collections.singletonList("multipart/form-data") : consumes);
        }
        return new ParameterFacts(parameters, requestBody);
    }

    /** Spring's default ModelAttribute binder writes JavaBean properties, not Jackson fields. */
    private static void modelParameters(List<ReachHttpApiParameter> parameters, ResolvableType type,
                                        String prefix, Set<String> visiting, Method method, int index) {
        Class<?> raw = type.resolve();
        if (raw == null || !projectObjectType(raw) || Map.class.isAssignableFrom(raw)
                || Iterable.class.isAssignableFrom(raw) || raw.isArray() || visiting.size() >= 6
                || !visiting.add(type.toString())) {
            throw new IllegalArgumentException("Cannot represent recursive or dynamic Spring MVC ModelAttribute binding for "
                    + method.toGenericString() + " parameter " + index + " property " + prefix);
        }
        try {
            int before = parameters.size();
            for (PropertyDescriptor property : BeanUtils.getPropertyDescriptors(raw)) {
                Method setter = property.getWriteMethod();
                if (setter == null) continue;
                String name = prefix + property.getName();
                ResolvableType value = ResolvableType.forMethodParameter(new MethodParameter(setter, 0), type);
                Class<?> valueClass = value.resolve(Object.class);
                if (BeanUtils.isSimpleProperty(valueClass)) {
                    ReachHttpApiParameter item = new ReachHttpApiParameter();
                    item.setLocation("QUERY");
                    item.setName(name);
                    // Bean construction/default values and optional validation are not an HTTP
                    // required=true declaration. Preserve optional binding without inventing one.
                    item.setRequired(false);
                    item.setSchema(nonNullSchema(schema(value)));
                    parameters.add(item);
                } else {
                    modelParameters(parameters, value, name + ".", visiting, method, index);
                }
            }
            if (parameters.size() == before) {
                throw new IllegalArgumentException("Cannot represent Spring MVC ModelAttribute with no writable properties for "
                        + method.toGenericString() + " parameter " + index);
            }
        } finally {
            visiting.remove(type.toString());
        }
    }

    private static boolean frameworkArgument(Class<?> raw) {
        for (String name : new String[]{"javax.servlet.ServletRequest", "javax.servlet.ServletResponse",
                "javax.servlet.http.HttpSession", "javax.servlet.http.PushBuilder", "jakarta.servlet.ServletRequest",
                "jakarta.servlet.ServletResponse", "jakarta.servlet.http.HttpSession", "jakarta.servlet.http.PushBuilder",
                "org.springframework.web.context.request.WebRequest", "org.springframework.ui.Model",
                "org.springframework.ui.ModelMap", "org.springframework.validation.Errors",
                "org.springframework.web.bind.support.SessionStatus", "org.springframework.web.servlet.mvc.support.RedirectAttributes",
                "org.springframework.web.util.UriComponentsBuilder", "java.security.Principal", "java.util.Locale",
                "java.util.TimeZone", "java.time.ZoneId", "java.io.InputStream", "java.io.Reader",
                "java.io.OutputStream", "java.io.Writer", "org.springframework.http.HttpMethod"}) {
            if (assignableTo(raw, name)) return true;
        }
        return false;
    }

    private static boolean assignableTo(Class<?> raw, String name) {
        if (raw == null) return false;
        if (name.equals(raw.getName()) || assignableTo(raw.getSuperclass(), name)) return true;
        for (Class<?> implemented : raw.getInterfaces()) if (assignableTo(implemented, name)) return true;
        return false;
    }

    private static boolean multipartType(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        if (Optional.class.equals(raw)) return multipartType(type.getGeneric(0));
        if (raw.isArray()) return multipartType(type.getComponentType());
        if (Iterable.class.isAssignableFrom(raw)) return multipartType(type.as(Iterable.class).getGeneric(0));
        return assignableTo(raw, "org.springframework.web.multipart.MultipartFile")
                || assignableTo(raw, "javax.servlet.http.Part") || assignableTo(raw, "jakarta.servlet.http.Part");
    }

    private static Map<String, Object> multipartSchema(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        if (Optional.class.equals(raw)) return multipartSchema(type.getGeneric(0));
        if (raw.isArray() || Iterable.class.isAssignableFrom(raw)) {
            Map<String, Object> schema = new LinkedHashMap<String, Object>();
            schema.put("type", "array");
            schema.put("items", multipartSchema(raw.isArray() ? type.getComponentType() : type.as(Iterable.class).getGeneric(0)));
            return schema;
        }
        if (!multipartType(type)) return nonNullSchema(schema(type));
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "string");
        schema.put("format", "binary");
        return schema;
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
        String actualName = parameter.isNamePresent() ? parameter.getName() : null;
        if (actualName == null && parameter.getDeclaringExecutable() instanceof Method) {
            String[] discovered = new DefaultParameterNameDiscoverer().getParameterNames((Method) parameter.getDeclaringExecutable());
            if (discovered != null && index < discovered.length) actualName = discovered[index];
        }
        String resolved = firstText(name, value, actualName);
        if (resolved == null) throw new IllegalArgumentException("Cannot determine Spring MVC parameter name; compile with -parameters or retain debug names for "
                + parameter.getDeclaringExecutable().toGenericString() + " parameter " + index);
        return resolved;
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
        return schema(ResolvableType.forType(type));
    }

    private static Map<String, Object> schema(ResolvableType type) {
        return schema(type, new LinkedHashSet<String>(), 0, null, false);
    }

    private static Map<String, Object> jsonSchema(ResolvableType type, ObjectMapper mapper) {
        return schema(type, new LinkedHashSet<String>(), 0, mapper, true);
    }

    private static Map<String, Object> schema(ResolvableType type, Set<String> visiting, int depth,
                                              ObjectMapper mapper, boolean json) {
        ResolvableType schemaType = type;
        while (Optional.class.equals(schemaType.resolve())) {
            schemaType = schemaType.getGeneric(0);
        }
        Class<?> raw = schemaType.resolve();
        if (raw == null || Void.TYPE.equals(raw) || Void.class.equals(raw)) {
            return raw == null ? Collections.<String, Object>emptyMap() : null;
        }
        if (Object.class.equals(raw)) {
            // Object/unresolved T can also be a JSON scalar or array. Do not invent an object shape.
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (raw.isEnum() && json) {
            return jsonEnumSchema(raw, mapper);
        } else if (stringType(raw) || raw.isEnum()) {
            result.put("type", "string");
        } else if (integerType(raw)) {
            result.put("type", "integer");
        } else if (Boolean.class.equals(raw) || Boolean.TYPE.equals(raw)) {
            result.put("type", "boolean");
        } else if (Number.class.isAssignableFrom(raw) || raw.isPrimitive()) {
            result.put("type", "number");
        } else if (raw.isArray() || Iterable.class.isAssignableFrom(raw)) {
            result.put("type", "array");
            ResolvableType itemType = raw.isArray() ? schemaType.getComponentType()
                    : schemaType.as(Iterable.class).getGeneric(0);
            result.put("items", nonNullSchema(schema(itemType, visiting, depth + 1, mapper, json)));
        } else if (Map.class.isAssignableFrom(raw)) {
            result.put("type", "object");
            result.put("additionalProperties", nonNullSchema(schema(schemaType.as(Map.class).getGeneric(1), visiting, depth + 1, mapper, json)));
        } else {
            result.put("type", "object");
            addObjectProperties(result, schemaType, visiting, depth, mapper, json);
        }
        return result;
    }

    /** JSON enum facts use the selected mapper; Spring path/query binding remains a string. */
    private static Map<String, Object> jsonEnumSchema(Class<?> raw, ObjectMapper mapper) {
        if (mapper == null) return Collections.emptyMap();
        String shape = null;
        try {
            Object[] constants = raw.getEnumConstants();
            if (constants == null || constants.length == 0 || constants.length > 256) return Collections.emptyMap();
            for (Object constant : constants) {
                JsonNode value = mapper.valueToTree(constant);
                String kind = value.isBoolean() ? "boolean" : value.isTextual() ? "string"
                        : value.isIntegralNumber() ? "integer" : value.isFloatingPointNumber() ? "number" : null;
                if (kind == null || shape != null && !shape.equals(kind)) return Collections.emptyMap();
                shape = kind;
            }
        } catch (RuntimeException unsupported) {
            // Mixed, custom structured, failed or unknown encodings must not invent a scalar.
            return Collections.emptyMap();
        }
        return Collections.<String, Object>singletonMap("type", shape);
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
                                            ResolvableType type,
                                            Set<String> visiting,
                                            int depth, ObjectMapper mapper, boolean json) {
        Class<?> raw = type.resolve();
        if (depth >= 6 || !projectObjectType(raw)) {
            return;
        }
        String key = type.toString();
        if (!visiting.add(key)) {
            return;
        }
        try {
            Map<String, Object> properties = new TreeMap<String, Object>();
            Set<String> required = new TreeSet<String>();
            for (ResolvableType owner = type; owner.resolve() != null && projectObjectType(owner.resolve());
                 owner = owner.getSuperType()) {
                for (Field field : owner.resolve().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                            || hasAnnotation(field, "JsonIgnore")) {
                        continue;
                    }
                    String property = jsonName(field, field.getName());
                    if (!properties.containsKey(property)) {
                        boolean customEnumField = json && field.getType().isEnum()
                                && (hasAnnotation(field, "JsonSerialize") || hasAnnotation(field, "JsonFormat"));
                        properties.put(property, customEnumField ? Collections.<String, Object>emptyMap()
                                : nonNullSchema(schema(ResolvableType.forField(field, owner), visiting, depth + 1, mapper, json)));
                        if (required(field)) {
                            required.add(property);
                        }
                    }
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

    private static Type unwrapOptional(Type type) {
        ResolvableType resolved = ResolvableType.forType(type);
        while (Optional.class.equals(resolved.resolve())) resolved = resolved.getGeneric(0);
        return resolved.getType();
    }

    private static ResolvableType responseBodyType(Type type) {
        ResolvableType result = ResolvableType.forType(type);
        while (responseWrapper(result.resolve())) {
            result = HttpEntity.class.isAssignableFrom(result.resolve())
                    ? result.as(HttpEntity.class).getGeneric(0) : result.getGeneric(0);
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
        return false;
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
