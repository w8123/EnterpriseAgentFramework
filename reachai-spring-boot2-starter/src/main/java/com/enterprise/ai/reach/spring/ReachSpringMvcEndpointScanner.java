package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Derives capability descriptors from the declared methods of a
 * {@code @RestController}. Methods already carrying an explicit
 * {@code @ReachCapability} are skipped, and each class/method path mapping is
 * expanded into one descriptor per combination of class and method path.
 */
final class ReachSpringMvcEndpointScanner {

    private ReachSpringMvcEndpointScanner() {
    }

    static List<ReachCapabilityDescriptor> scanClass(Class<?> type) {
        List<ReachCapabilityDescriptor> descriptors = new ArrayList<ReachCapabilityDescriptor>();
        if (type == null || !isController(type)) {
            return descriptors;
        }
        List<String> classPaths = classPaths(type);
        for (Method method : type.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(method, ReachCapability.class)) {
                continue;
            }
            Mapping mapping = methodMapping(method);
            if (mapping == null) {
                continue;
            }
            for (String classPath : classPaths) {
                for (String methodPath : mapping.paths) {
                    descriptors.add(descriptor(type, method, mapping.httpMethod, combinePath(classPath, methodPath)));
                }
            }
        }
        return descriptors;
    }

    private static boolean isController(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, RestController.class);
    }

    private static List<String> classPaths(Class<?> type) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        return paths(mapping == null ? null : mapping.path(), mapping == null ? null : mapping.value());
    }

    private static Mapping methodMapping(Method method) {
        GetMapping get = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
        if (get != null) {
            return new Mapping("GET", paths(get.path(), get.value()));
        }
        PostMapping post = AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class);
        if (post != null) {
            return new Mapping("POST", paths(post.path(), post.value()));
        }
        PutMapping put = AnnotatedElementUtils.findMergedAnnotation(method, PutMapping.class);
        if (put != null) {
            return new Mapping("PUT", paths(put.path(), put.value()));
        }
        DeleteMapping delete = AnnotatedElementUtils.findMergedAnnotation(method, DeleteMapping.class);
        if (delete != null) {
            return new Mapping("DELETE", paths(delete.path(), delete.value()));
        }
        PatchMapping patch = AnnotatedElementUtils.findMergedAnnotation(method, PatchMapping.class);
        if (patch != null) {
            return new Mapping("PATCH", paths(patch.path(), patch.value()));
        }
        RequestMapping request = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (request == null) {
            return null;
        }
        RequestMethod[] methods = request.method();
        String httpMethod = methods == null || methods.length == 0 ? "GET" : methods[0].name();
        return new Mapping(httpMethod, paths(request.path(), request.value()));
    }

    private static ReachCapabilityDescriptor descriptor(Class<?> type, Method method, String httpMethod, String endpointPath) {
        ReachCapabilityDescriptor descriptor = new ReachCapabilityDescriptor();
        descriptor.setName(toolName(endpointPath, method.getName()));
        descriptor.setTitle(title(method));
        descriptor.setDescription(title(method));
        descriptor.setDomain(firstPathSegment(endpointPath));
        descriptor.setModule(type.getSimpleName());
        descriptor.setTags(Arrays.asList("Spring MVC", httpMethod));
        descriptor.setSideEffect("GET".equalsIgnoreCase(httpMethod) ? ReachSideEffectLevel.READ : ReachSideEffectLevel.WRITE);
        descriptor.setClassName(type.getName());
        descriptor.setMethodName(method.getName());
        descriptor.setHttpMethod(httpMethod);
        descriptor.setEndpointPath(endpointPath);
        descriptor.setRequestBodyType(requestBodyType(method));
        descriptor.setReturnType(method.getGenericReturnType().getTypeName());
        descriptor.setParameters(parameters(method));
        return descriptor;
    }

    private static List<ReachCapabilityParameter> parameters(Method method) {
        List<ReachCapabilityParameter> out = new ArrayList<ReachCapabilityParameter>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ReachCapabilityParameter item = new ReachCapabilityParameter();
            item.setName(parameterName(parameter, i));
            item.setType(typeName(parameter.getType()));
            item.setRequired(required(parameter));
            item.setDescription(item.getName());
            out.add(item);
        }
        return out;
    }

    private static String parameterName(Parameter parameter, int index) {
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        if (requestParam != null) {
            return firstText(requestParam.name(), requestParam.value(), parameter.isNamePresent() ? parameter.getName() : null, "arg" + index);
        }
        PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
        if (pathVariable != null) {
            return firstText(pathVariable.name(), pathVariable.value(), parameter.isNamePresent() ? parameter.getName() : null, "arg" + index);
        }
        return parameter.isNamePresent() ? parameter.getName() : "arg" + index;
    }

    private static boolean required(Parameter parameter) {
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        return requestParam == null || requestParam.required();
    }

    private static String requestBodyType(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getAnnotation(RequestBody.class) != null) {
                return parameter.getParameterizedType().getTypeName();
            }
        }
        return null;
    }

    private static String title(Method method) {
        String apiOperation = annotationString(method, "io.swagger.annotations.ApiOperation", "value");
        return StringUtils.hasText(apiOperation) ? apiOperation : method.getName();
    }

    private static String annotationString(Method method, String annotationName, String attribute) {
        for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
            if (!annotation.annotationType().getName().equals(annotationName)) {
                continue;
            }
            try {
                Object value = annotation.annotationType().getMethod(attribute).invoke(annotation);
                return value == null ? null : String.valueOf(value);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> paths(String[] path, String[] value) {
        List<String> out = new ArrayList<String>();
        if (path != null) {
            for (String item : path) {
                if (StringUtils.hasText(item)) {
                    out.add(item.trim());
                }
            }
        }
        if (out.isEmpty() && value != null) {
            for (String item : value) {
                if (StringUtils.hasText(item)) {
                    out.add(item.trim());
                }
            }
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
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
        while (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String toolName(String endpointPath, String fallback) {
        String text = normalizePath(endpointPath).replaceAll("[{}]", "").replaceAll("[^A-Za-z0-9]+", "_");
        while (text.startsWith("_")) {
            text = text.substring(1);
        }
        while (text.endsWith("_")) {
            text = text.substring(0, text.length() - 1);
        }
        return StringUtils.hasText(text) ? text : fallback;
    }

    private static String firstPathSegment(String endpointPath) {
        String text = normalizePath(endpointPath);
        String[] parts = text.split("/");
        return parts.length > 1 && StringUtils.hasText(parts[1]) ? parts[1] : null;
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

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static class Mapping {
        private final String httpMethod;
        private final List<String> paths;

        private Mapping(String httpMethod, List<String> paths) {
            this.httpMethod = httpMethod;
            this.paths = paths;
        }
    }
}
