package com.enterprise.ai.text.tooling.scanner.controller;

import com.enterprise.ai.text.tooling.scanner.manifest.ParameterLocation;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolParameterDefinition;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.enterprise.ai.text.tooling.scanner.ScanOptions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 解析 {@code @RequestBody} DTO 类的字段结构（含嵌套子字段），结果作为
 * {@link ToolParameterDefinition#children()} 挂在 body_json 占位参数上，仅用于展示与 AI 文档上下文，
 * 不参与运行时调用。
 */
class RequestBodySchemaExtractor {

    private static final int MAX_DEPTH = 6;

    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "String", "CharSequence",
            "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte",
            "double", "Double", "float", "Float", "BigDecimal", "BigInteger", "Number",
            "boolean", "Boolean",
            "char", "Character",
            "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "ZonedDateTime",
            "Instant", "Date", "Timestamp",
            "UUID", "URI", "URL",
            "Object", "JsonNode", "Void"
    );

    private static final Set<String> CONTAINER_TYPES = Set.of(
            "List", "Set", "Collection", "Iterable", "Queue", "Deque", "Optional"
    );

    private final List<String> paramOrder;

    RequestBodySchemaExtractor() {
        this.paramOrder = null;
    }

    /**
     * @param paramOrder 为 null 时沿用旧有固定优先级
     */
    RequestBodySchemaExtractor(List<String> paramOrder) {
        this.paramOrder = paramOrder;
    }

    List<ToolParameterDefinition> extract(String typeLiteral,
                                          Map<String, TypeDeclaration<?>> classIndex) {
        return resolveFields(typeLiteral, classIndex, new HashSet<>(), 0);
    }

    private List<ToolParameterDefinition> resolveFields(String typeLiteral,
                                                        Map<String, TypeDeclaration<?>> classIndex,
                                                        Set<String> visited,
                                                        int depth) {
        if (depth >= MAX_DEPTH) {
            return List.of();
        }
        String target = unwrapContainer(typeLiteral);
        if (target == null || isPrimitiveType(target)) {
            return List.of();
        }
        TypeDeclaration<?> declaration = lookup(target, classIndex);
        if (declaration == null) {
            return List.of();
        }
        String key = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        if (visited.contains(key)) {
            return List.of();
        }
        visited.add(key);
        try {
            List<ToolParameterDefinition> params = new ArrayList<>();
            if (declaration instanceof RecordDeclaration recordDeclaration) {
                for (Parameter component : recordDeclaration.getParameters()) {
                    params.add(parameterFromComponent(component, classIndex, visited, depth));
                }
            } else if (declaration instanceof ClassOrInterfaceDeclaration classDecl) {
                for (FieldDeclaration field : classDecl.getFields()) {
                    if (field.isStatic()) {
                        continue;
                    }
                    if (hasAnnotation(field.getAnnotations(), "JsonIgnore")) {
                        continue;
                    }
                    for (VariableDeclarator variable : field.getVariables()) {
                        params.add(parameterFromField(field, variable, classIndex, visited, depth));
                    }
                }
            }
            return params;
        } finally {
            visited.remove(key);
        }
    }

    private ToolParameterDefinition parameterFromComponent(Parameter component,
                                                           Map<String, TypeDeclaration<?>> classIndex,
                                                           Set<String> visited,
                                                           int depth) {
        String rawType = component.getType().asString();
        String displayName = jsonPropertyName(component.getAnnotations(), component.getNameAsString());
        String description = describeByOrder(component.getAnnotations(), null, component.getNameAsString());
        boolean required = isRequired(component.getAnnotations());
        List<ToolParameterDefinition> children = resolveFields(rawType, classIndex, visited, depth + 1);
        return new ToolParameterDefinition(
                displayName,
                mapDisplayType(rawType),
                description,
                required,
                ParameterLocation.BODY,
                children
        );
    }

    private ToolParameterDefinition parameterFromField(FieldDeclaration field,
                                                       VariableDeclarator variable,
                                                       Map<String, TypeDeclaration<?>> classIndex,
                                                       Set<String> visited,
                                                       int depth) {
        String rawType = variable.getType().asString();
        String displayName = jsonPropertyName(field.getAnnotations(), variable.getNameAsString());
        String javadoc = field.getJavadoc()
                .map(jd -> jd.getDescription().toText().trim())
                .filter(text -> !text.isBlank())
                .orElse(null);
        String description = describeByOrder(field.getAnnotations(), javadoc, variable.getNameAsString());
        boolean required = isRequired(field.getAnnotations());
        List<ToolParameterDefinition> children = resolveFields(rawType, classIndex, visited, depth + 1);
        return new ToolParameterDefinition(
                displayName,
                mapDisplayType(rawType),
                description,
                required,
                ParameterLocation.BODY,
                children
        );
    }

    /**
     * 拆掉 {@code List<T>}、{@code Optional<T>}、{@code T[]}、{@code Map<K,V>} 等容器，返回待递归的实际元素类型字面量。
     * 无法识别时原样返回。
     */
    private String unwrapContainer(String typeLiteral) {
        if (typeLiteral == null) {
            return null;
        }
        String trimmed = typeLiteral.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.endsWith("[]")) {
            return unwrapContainer(trimmed.substring(0, trimmed.length() - 2));
        }
        int angle = trimmed.indexOf('<');
        if (angle < 0) {
            return trimmed;
        }
        if (!trimmed.endsWith(">")) {
            return trimmed;
        }
        String raw = trimmed.substring(0, angle).trim();
        String inside = trimmed.substring(angle + 1, trimmed.length() - 1).trim();
        String rawSimple = simpleName(raw);
        if ("Map".equals(rawSimple) || "HashMap".equals(rawSimple) || "LinkedHashMap".equals(rawSimple) || "TreeMap".equals(rawSimple)) {
            int comma = splitCommaDepth(inside);
            if (comma < 0) {
                return trimmed;
            }
            return unwrapContainer(inside.substring(comma + 1).trim());
        }
        if (CONTAINER_TYPES.contains(rawSimple)) {
            return unwrapContainer(inside);
        }
        // 其它泛型（如 Page<User>）：退而求其次，递归泛型第一个参数
        int comma = splitCommaDepth(inside);
        String first = comma < 0 ? inside : inside.substring(0, comma).trim();
        return unwrapContainer(first);
    }

    private int splitCommaDepth(String literal) {
        int depth = 0;
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 基本类型检测：比较 simple name。无法识别（或泛型字母 T/E 等）时当作基本类型，避免递归到类型变量上。
     */
    private boolean isPrimitiveType(String typeLiteral) {
        String simple = simpleName(typeLiteral);
        if (PRIMITIVE_TYPES.contains(simple)) {
            return true;
        }
        // 单字母泛型形参（T、E、K、V、R...）
        return simple.length() == 1 && Character.isUpperCase(simple.charAt(0));
    }

    private String simpleName(String typeLiteral) {
        String core = typeLiteral;
        int angle = core.indexOf('<');
        if (angle > 0) {
            core = core.substring(0, angle);
        }
        int dot = core.lastIndexOf('.');
        if (dot >= 0) {
            core = core.substring(dot + 1);
        }
        return core.trim();
    }

    private TypeDeclaration<?> lookup(String typeLiteral, Map<String, TypeDeclaration<?>> classIndex) {
        if (typeLiteral == null || typeLiteral.isBlank()) {
            return null;
        }
        TypeDeclaration<?> direct = classIndex.get(typeLiteral);
        if (direct != null) {
            return direct;
        }
        return classIndex.get(simpleName(typeLiteral));
    }

    /**
     * 展示字段用的类型名（保留泛型 / 数组写法），剥去包路径，便于前端阅读。
     */
    private String mapDisplayType(String rawType) {
        if (rawType == null) {
            return "string";
        }
        String trimmed = rawType.trim();
        if (trimmed.isEmpty()) {
            return "string";
        }
        String simple = simpleName(trimmed);
        return switch (simple) {
            case "String", "CharSequence" -> "string";
            case "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte", "BigInteger" -> "integer";
            case "double", "Double", "float", "Float", "BigDecimal", "Number" -> "number";
            case "boolean", "Boolean" -> "boolean";
            default -> stripPackages(trimmed);
        };
    }

    private String stripPackages(String typeLiteral) {
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

    private String jsonPropertyName(NodeList<AnnotationExpr> annotations, String fallback) {
        return annotations.stream()
                .filter(annotation -> "JsonProperty".equals(annotation.getNameAsString()))
                .findFirst()
                .flatMap(annotation -> extractNamedMember(annotation, "value"))
                .map(this::extractStringValue)
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }

    private String describeByOrder(NodeList<AnnotationExpr> annotations, String javadoc, String fallback) {
        if (paramOrder == null) {
            return resolveDescription(annotations, javadoc, fallback);
        }
        if (paramOrder.isEmpty()) {
            return fallback;
        }
        for (String k : paramOrder) {
            Optional<String> t = tryDescribeSource(annotations, javadoc, fallback, k);
            if (t.isPresent() && !t.get().isBlank()) {
                return t.get();
            }
        }
        return fallback;
    }

    private Optional<String> tryDescribeSource(NodeList<AnnotationExpr> annotations,
                                              String javadoc, String fieldName, String key) {
        if (key == null) {
            return Optional.empty();
        }
        String u = key.trim();
        if (ScanOptions.PS_JD.equals(u) || "JAVADOC_PARAM".equals(u)) {
            if (javadoc != null && !javadoc.isBlank()) {
                return Optional.of(javadoc);
            }
            return Optional.empty();
        }
        if (ScanOptions.PS_SCHEMA.equals(u) || "SCHEMA_ANNO".equals(u)) {
            return annotationStringAttr(annotations, "Schema", "description");
        }
        if (ScanOptions.PS_PARAM.equals(u) || "PARAMETER_ANNO".equals(u)) {
            return annotationStringAttr(annotations, "Parameter", "description")
                    .or(() -> annotationStringAttr(annotations, "Parameter", "value"));
        }
        if (ScanOptions.PS_FIELD.equals(u) || "FIELD_NAME".equals(u)) {
            return Optional.ofNullable(fieldName);
        }
        return Optional.empty();
    }

    private String resolveDescription(NodeList<AnnotationExpr> annotations, String javadoc, String fallback) {
        if (javadoc != null && !javadoc.isBlank()) {
            return javadoc;
        }
        Optional<String> schemaDesc = annotationStringAttr(annotations, "Schema", "description");
        if (schemaDesc.isPresent()) {
            return schemaDesc.get();
        }
        Optional<String> apiModelDesc = annotationStringAttr(annotations, "ApiModelProperty", "value")
                .or(() -> annotationStringAttr(annotations, "ApiModelProperty", "notes"));
        return apiModelDesc.orElse(fallback);
    }

    private boolean isRequired(NodeList<AnnotationExpr> annotations) {
        if (hasAnnotation(annotations, "NotNull")
                || hasAnnotation(annotations, "NotBlank")
                || hasAnnotation(annotations, "NotEmpty")) {
            return true;
        }
        if (annotationBooleanAttr(annotations, "Schema", "required").orElse(false)) {
            return true;
        }
        if (annotationBooleanAttr(annotations, "ApiModelProperty", "required").orElse(false)) {
            return true;
        }
        return annotationBooleanAttr(annotations, "JsonProperty", "required").orElse(false);
    }

    private boolean hasAnnotation(NodeList<AnnotationExpr> annotations, String simpleName) {
        return annotations.stream().anyMatch(annotation -> simpleName.equals(annotation.getNameAsString()));
    }

    private Optional<String> annotationStringAttr(NodeList<AnnotationExpr> annotations,
                                                  String annotationName,
                                                  String attribute) {
        return annotations.stream()
                .filter(annotation -> annotationName.equals(annotation.getNameAsString()))
                .findFirst()
                .flatMap(annotation -> extractNamedMember(annotation, attribute))
                .map(this::extractStringValue)
                .filter(value -> !value.isBlank());
    }

    private Optional<Boolean> annotationBooleanAttr(NodeList<AnnotationExpr> annotations,
                                                    String annotationName,
                                                    String attribute) {
        return annotations.stream()
                .filter(annotation -> annotationName.equals(annotation.getNameAsString()))
                .findFirst()
                .flatMap(annotation -> extractNamedMember(annotation, attribute))
                .map(Expression::toString)
                .map(raw -> raw.replace("\"", "").trim().toLowerCase(Locale.ROOT))
                .filter(raw -> "true".equals(raw) || "false".equals(raw))
                .map(Boolean::parseBoolean);
    }

    private Optional<Expression> extractNamedMember(AnnotationExpr annotation, String name) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
            return "value".equals(name) ? Optional.of(singleMember.getMemberValue()) : Optional.empty();
        }
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            return normalAnnotation.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(name))
                    .map(MemberValuePair::getValue)
                    .findFirst();
        }
        return Optional.empty();
    }

    private String extractStringValue(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return expression.asStringLiteralExpr().asString();
        }
        if (expression.isNameExpr()) {
            return expression.asNameExpr().getNameAsString();
        }
        return expression.toString().replace("\"", "");
    }
}
