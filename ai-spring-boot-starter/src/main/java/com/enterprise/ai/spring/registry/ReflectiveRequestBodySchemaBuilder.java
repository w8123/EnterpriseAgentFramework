package com.enterprise.ai.spring.registry;

import com.enterprise.ai.skill.AiParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 运行时反射展开 {@code @RequestBody} 类型为嵌套 {@link EafCapabilityParameter} 列表，
 * 与离线扫描器在 {@code body_json} 下挂 {@code children} 的约定对齐（仅用于目录展示与 AI 语义）。
 */
public class ReflectiveRequestBodySchemaBuilder {

    private static final int MAX_DEPTH = 6;

    private static final Set<String> PRIMITIVE_LIKE = Set.of(
            "String", "CharSequence",
            "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte",
            "double", "Double", "float", "Float", "BigDecimal", "BigInteger", "Number",
            "boolean", "Boolean",
            "char", "Character",
            "void", "Void",
            "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "ZonedDateTime",
            "Instant", "Date", "Timestamp",
            "UUID", "URI", "URL",
            "Object", "Enum", "Class"
    );

    private static final Set<String> CONTAINER_SIMPLE = Set.of(
            "List", "Set", "Collection", "Iterable", "Queue", "Deque", "Optional"
    );

    private final RuntimeCapabilityMetadataResolver metadataResolver;

    public ReflectiveRequestBodySchemaBuilder(RuntimeCapabilityMetadataResolver metadataResolver) {
        this.metadataResolver = metadataResolver;
    }

    public List<EafCapabilityParameter> expand(Class<?> rawType) {
        if (rawType == null || rawType == void.class || rawType == Void.class) {
            return List.of();
        }
        return expandResolved(rawType, new HashSet<>(), 0);
    }

    private List<EafCapabilityParameter> expandResolved(Class<?> erased,
                                                        Set<String> visited,
                                                        int depth) {
        if (depth >= MAX_DEPTH) {
            return List.of();
        }
        if (erased.isPrimitive() || erased.isEnum() || isPrimitiveLike(erased)) {
            return List.of();
        }
        String visitKey = erased.getName();
        if (visited.contains(visitKey)) {
            return List.of();
        }
        visited.add(visitKey);
        try {
            if (erased.isRecord()) {
                return expandRecord(erased, visited, depth);
            }
            return expandClassFields(erased, visited, depth);
        } finally {
            visited.remove(visitKey);
        }
    }

    private List<EafCapabilityParameter> expandRecord(Class<?> recordClass, Set<String> visited, int depth) {
        RecordComponent[] components = recordClass.getRecordComponents();
        List<EafCapabilityParameter> out = new ArrayList<>();
        for (RecordComponent rc : components) {
            if (shouldSkipMember(rc)) {
                continue;
            }
            String displayName = jsonPropertyName(rc, rc.getName());
            Class<?> fieldErased = rc.getType();
            Type generic = rc.getGenericType();
            Class<?> innerDisplay = resolveComponentType(generic, fieldErased);
            String typeLabel = mapDisplayType(innerDisplay != null ? innerDisplay : fieldErased, generic);
            String description = metadataResolver.resolveMemberDescription(rc, displayName);
            boolean required = isRequiredMember(rc);
            Map<String, Object> meta = extractAiParamMetadata(rc);
            List<EafCapabilityParameter> children = expandNestedChildren(fieldErased, generic, visited, depth);
            out.add(new EafCapabilityParameter(
                    displayName,
                    typeLabel,
                    description,
                    required,
                    "BODY",
                    children,
                    meta.isEmpty() ? null : meta
            ));
        }
        return out;
    }

    private List<EafCapabilityParameter> expandClassFields(Class<?> erased,
                                                             Set<String> visited,
                                                             int depth) {
        List<EafCapabilityParameter> out = new ArrayList<>();
        for (Class<?> c = erased; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (shouldSkipMember(field)) {
                    continue;
                }
                field.setAccessible(true);
                String displayName = jsonPropertyName(field, field.getName());
                Type generic = field.getGenericType();
                Class<?> fieldErased = field.getType();
                Class<?> innerDisplay = resolveComponentType(generic, fieldErased);
                String typeLabel = mapDisplayType(innerDisplay != null ? innerDisplay : fieldErased, generic);
                String description = metadataResolver.resolveMemberDescription(field, displayName);
                boolean required = isRequiredMember(field);
                Map<String, Object> meta = extractAiParamMetadata(field);
                List<EafCapabilityParameter> children = expandNestedChildren(fieldErased, generic, visited, depth);
                out.add(new EafCapabilityParameter(
                        displayName,
                        typeLabel,
                        description,
                        required,
                        "BODY",
                        children,
                        meta.isEmpty() ? null : meta
                ));
            }
        }
        return out;
    }

    private List<EafCapabilityParameter> expandNestedChildren(Class<?> erased,
                                                                Type generic,
                                                                Set<String> visited,
                                                                int depth) {
        Class<?> component = unwrapContainerClass(erased, generic);
        if (component == null || isPrimitiveLike(component) || component.isEnum()) {
            return List.of();
        }
        return expandResolved(component, visited, depth + 1);
    }

    private static Class<?> unwrapContainerClass(Class<?> erased, Type generic) {
        if (erased.isArray()) {
            return erased.getComponentType();
        }
        String simple = simpleName(erased);
        if (CONTAINER_SIMPLE.contains(simple) && generic instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0) {
                return resolveErasedClass(args[0]);
            }
        }
        if ("Map".equals(simple) || "HashMap".equals(simple) || "LinkedHashMap".equals(simple) || "TreeMap".equals(simple)) {
            if (generic instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length >= 2) {
                    return resolveErasedClass(args[1]);
                }
            }
            return Object.class;
        }
        if (isPrimitiveLike(erased) || erased.isEnum()) {
            return null;
        }
        return erased;
    }

    private static Class<?> resolveComponentType(Type generic, Class<?> fallbackErased) {
        Class<?> fromGen = unwrapFromGenericOnly(generic);
        if (fromGen != null) {
            return fromGen;
        }
        return unwrapContainerClass(fallbackErased, generic);
    }

    private static Class<?> unwrapFromGenericOnly(Type generic) {
        if (generic instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rc) {
                String simple = simpleName(rc);
                if (CONTAINER_SIMPLE.contains(simple)) {
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length > 0) {
                        return resolveErasedClass(args[0]);
                    }
                }
                if ("Map".equals(simple) || "HashMap".equals(simple) || "LinkedHashMap".equals(simple) || "TreeMap".equals(simple)) {
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length >= 2) {
                        return resolveErasedClass(args[1]);
                    }
                }
            }
        }
        return null;
    }

    private static Class<?> resolveErasedClass(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        if (t instanceof WildcardType wt) {
            Type[] uppers = wt.getUpperBounds();
            if (uppers.length > 0) {
                return resolveErasedClass(uppers[0]);
            }
        }
        return null;
    }

    private static boolean isPrimitiveLike(Class<?> c) {
        if (c == null) {
            return true;
        }
        if (c.isPrimitive()) {
            return true;
        }
        Package p = c.getPackage();
        String pkg = p == null ? "" : p.getName();
        if (pkg.startsWith("java.lang") && PRIMITIVE_LIKE.contains(c.getSimpleName())) {
            return true;
        }
        if ("java.math.BigDecimal".equals(c.getName()) || "java.math.BigInteger".equals(c.getName())) {
            return true;
        }
        if (pkg.startsWith("java.time.")) {
            return true;
        }
        if (c.isEnum()) {
            return true;
        }
        if (Throwable.class.isAssignableFrom(c)) {
            return true;
        }
        // multipart / binary
        if (c.getSimpleName().equals("MultipartFile")) {
            return true;
        }
        if (c.getName().equals("org.springframework.web.multipart.MultipartFile")) {
            return true;
        }
        // single-letter type variables T, E...
        String sn = c.getSimpleName();
        return sn.length() == 1 && Character.isUpperCase(sn.charAt(0));
    }

    private static String mapDisplayType(Class<?> erased, Type generic) {
        if (erased == null) {
            return "string";
        }
        if (erased.isArray()) {
            return mapDisplayType(erased.getComponentType(), erased.getComponentType()) + "[]";
        }
        String simple = simpleName(erased);
        if ("String".equals(simple) || "CharSequence".equals(simple)) {
            return "string";
        }
        if (List.of("int", "Integer", "long", "Long", "short", "Short", "byte", "Byte", "BigInteger").contains(simple)) {
            return "integer";
        }
        if (List.of("double", "Double", "float", "Float", "BigDecimal", "Number").contains(simple)) {
            return "number";
        }
        if ("boolean".equals(simple) || "Boolean".equals(simple)) {
            return "boolean";
        }
        if (generic instanceof ParameterizedType pt) {
            return stripPackages(pt.toString());
        }
        return stripPackages(erased.getTypeName());
    }

    private static String stripPackages(String typeLiteral) {
        if (typeLiteral == null || typeLiteral.isBlank()) {
            return "string";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < typeLiteral.length()) {
            char c = typeLiteral.charAt(i);
            if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < typeLiteral.length()
                        && (Character.isJavaIdentifierPart(typeLiteral.charAt(j)) || typeLiteral.charAt(j) == '.')) {
                    j++;
                }
                String token = typeLiteral.substring(i, j);
                int dot = token.lastIndexOf('.');
                sb.append(dot >= 0 ? token.substring(dot + 1) : token);
                i = j;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String simpleName(Class<?> c) {
        if (c.isArray()) {
            return simpleName(c.getComponentType()) + "[]";
        }
        return c.getSimpleName();
    }

    private static boolean shouldSkipMember(AnnotatedElement el) {
        if (hasAnnotationSimple(el, "JsonIgnore")) {
            return true;
        }
        if (hasAnnotationByClassName(el, "com.fasterxml.jackson.annotation.JsonIgnore")) {
            return true;
        }
        return hasAnnotationByClassName(el, "jakarta.json.bind.annotation.JsonbTransient");
    }

    private static boolean hasAnnotationSimple(AnnotatedElement el, String simple) {
        for (Annotation a : el.getAnnotations()) {
            if (simple.equals(a.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotationByClassName(AnnotatedElement el, String className) {
        try {
            Class<?> annType = Class.forName(className);
            return el.getAnnotation(annType.asSubclass(Annotation.class)) != null;
        } catch (ClassNotFoundException | ClassCastException e) {
            return false;
        }
    }

    private static String jsonPropertyName(AnnotatedElement el, String fallback) {
        for (Annotation a : el.getAnnotations()) {
            if (!"JsonProperty".equals(a.annotationType().getSimpleName())) {
                continue;
            }
            Optional<String> v = invokeStringAttr(a, "value");
            if (v.isPresent() && !v.get().isBlank()) {
                return v.get();
            }
            return invokeStringAttr(a, "name").filter(s -> !s.isBlank()).orElse(fallback);
        }
        return fallback;
    }

    private static Optional<String> invokeStringAttr(Annotation ann, String attr) {
        try {
            java.lang.reflect.Method m = ann.annotationType().getMethod(attr);
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

    private static boolean isRequiredMember(AnnotatedElement el) {
        AiParam ap = el.getAnnotation(AiParam.class);
        if (ap != null && ap.required()) {
            return true;
        }
        if (hasAnnotationSimple(el, "NotNull")
                || hasAnnotationSimple(el, "NotBlank")
                || hasAnnotationSimple(el, "NotEmpty")) {
            return true;
        }
        for (Annotation a : el.getAnnotations()) {
            if ("Schema".equals(a.annotationType().getSimpleName())) {
                if (invokeBooleanAttr(a, "required").orElse(false)) {
                    return true;
                }
            }
            if ("JsonProperty".equals(a.annotationType().getSimpleName())) {
                if (invokeBooleanAttr(a, "required").orElse(false)) {
                    return true;
                }
            }
            if ("ApiModelProperty".equals(a.annotationType().getSimpleName())) {
                if (invokeBooleanAttr(a, "required").orElse(false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Optional<Boolean> invokeBooleanAttr(Annotation ann, String attr) {
        try {
            java.lang.reflect.Method m = ann.annotationType().getMethod(attr);
            m.setAccessible(true);
            Object v = m.invoke(ann);
            if (v instanceof Boolean b) {
                return Optional.of(b);
            }
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> extractAiParamMetadata(AnnotatedElement el) {
        AiParam ai = el.getAnnotation(AiParam.class);
        if (ai == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (!ai.example().isBlank()) {
            m.put("example", ai.example());
        }
        if (!ai.sourceHint().isBlank()) {
            m.put("sourceHint", ai.sourceHint());
        }
        if (!ai.dictType().isBlank()) {
            m.put("dictType", ai.dictType());
        }
        m.put("sensitive", ai.sensitive());
        return m;
    }
}
