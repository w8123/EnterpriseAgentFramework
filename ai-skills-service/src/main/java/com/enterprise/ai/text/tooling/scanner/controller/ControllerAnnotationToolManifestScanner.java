package com.enterprise.ai.text.tooling.scanner.controller;

import com.enterprise.ai.text.tooling.scanner.manifest.ParameterLocation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolSource;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 基于 Spring MVC 注解扫描 Controller，生成运行时可消费的扫描结果。
 */
public class ControllerAnnotationToolManifestScanner {

    private static final Logger log = LoggerFactory.getLogger(ControllerAnnotationToolManifestScanner.class);
    private final RequestBodySchemaExtractor bodySchemaExtractor = new RequestBodySchemaExtractor();
    private static final int MAX_OPERATION_DESCRIPTION_CHARS = 512;
    private static final Set<String> GENERIC_METHOD_NAMES = Set.of("test", "execute", "handle", "process");
    private static final Set<String> DEFAULT_IGNORED_PATH_SEGMENTS = Set.of(
            ".git",
            ".idea",
            ".project-store",
            "node_modules",
            "target",
            "build",
            "dist",
            "out",
            ".svn"
    );

    public ToolManifest scan(Path sourcePath, ProjectMetadata projectMetadata) {
        List<Path> javaFiles = collectJavaFiles(sourcePath);
        Map<String, TypeDeclaration<?>> classIndex = buildClassIndex(javaFiles);
        List<ToolDefinition> tools = new ArrayList<>();
        javaFiles.forEach(javaFile -> {
            try {
                tools.addAll(scanFile(javaFile, projectMetadata, classIndex));
            } catch (IllegalArgumentException ex) {
                if (isParseFailure(ex)) {
                    log.warn("Skip unparsable controller source: {}", javaFile, ex);
                    return;
                }
                throw ex;
            }
        });
        ToolManifest manifest = new ToolManifest(projectMetadata, ensureUniqueToolNames(tools));
        manifest.validate();
        return manifest;
    }

    /**
     * 扫描前把所有可解析的类型声明建索引（FQN + simple name），供 {@code @RequestBody} DTO 字段解析按类名查找。
     */
    private Map<String, TypeDeclaration<?>> buildClassIndex(List<Path> javaFiles) {
        Map<String, TypeDeclaration<?>> index = new HashMap<>();
        for (Path javaFile : javaFiles) {
            CompilationUnit unit;
            try {
                unit = parse(javaFile);
            } catch (IllegalArgumentException ex) {
                if (isParseFailure(ex)) {
                    continue;
                }
                throw ex;
            }
            for (TypeDeclaration<?> declaration : unit.findAll(TypeDeclaration.class)) {
                declaration.getFullyQualifiedName().ifPresent(fqn -> index.putIfAbsent(fqn, declaration));
                index.putIfAbsent(declaration.getNameAsString(), declaration);
            }
        }
        return index;
    }

    private List<ToolDefinition> scanFile(Path javaFile,
                                          ProjectMetadata projectMetadata,
                                          Map<String, TypeDeclaration<?>> classIndex) {
        CompilationUnit compilationUnit = parse(javaFile);
        List<ToolDefinition> tools = new ArrayList<>();

        for (ClassOrInterfaceDeclaration declaration : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!isController(declaration)) {
                continue;
            }

            String basePath = extractRequestMappingPath(declaration.getAnnotations()).orElse("");
            for (MethodDeclaration method : declaration.getMethods()) {
                Optional<MappingDefinition> mapping = extractMethodMapping(method);
                if (mapping.isEmpty()) {
                    continue;
                }

                MappingDefinition definition = mapping.get();
                List<ToolParameterDefinition> parameters = extractParameters(method, classIndex);
                String requestBodyType = extractRequestBodyType(method);

                tools.add(new ToolDefinition(
                        resolveToolName(method, joinPath(basePath, definition.path())),
                        resolveToolDescription(method),
                        definition.httpMethod(),
                        joinPath(basePath, definition.path()),
                        definition.httpMethod() + " " + joinPath(projectMetadata.contextPath(), joinPath(basePath, definition.path())),
                        parameters,
                        requestBodyType,
                        extractResponseType(method),
                        new ToolSource(
                                "controller",
                                javaFile.getFileName() + "#" + declaration.getNameAsString() + "#" + method.getNameAsString()
                        )
                ));
            }
        }

        return tools;
    }

    private List<ToolParameterDefinition> extractParameters(MethodDeclaration method,
                                                            Map<String, TypeDeclaration<?>> classIndex) {
        List<ToolParameterDefinition> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (hasAnnotation(parameter, "RequestBody")) {
                String rawType = parameter.getType().asString();
                List<ToolParameterDefinition> children = bodySchemaExtractor.extract(rawType, classIndex);
                parameters.add(new ToolParameterDefinition(
                        "body_json",
                        "json",
                        "JSON 请求体，对应 " + rawType,
                        annotationBooleanValue(parameter, "RequestBody", "required").orElse(true),
                        ParameterLocation.BODY,
                        children
                ));
                continue;
            }

            if (hasAnnotation(parameter, "PathVariable")) {
                parameters.add(new ToolParameterDefinition(
                        annotationStringValue(parameter, "PathVariable", "value").orElse(parameter.getNameAsString()),
                        mapJavaType(parameter.getType().asString()),
                        parameter.getNameAsString(),
                        true,
                        ParameterLocation.PATH
                ));
                continue;
            }

            if (hasAnnotation(parameter, "RequestParam")) {
                parameters.add(new ToolParameterDefinition(
                        annotationStringValue(parameter, "RequestParam", "value").orElse(parameter.getNameAsString()),
                        mapJavaType(parameter.getType().asString()),
                        parameter.getNameAsString(),
                        annotationBooleanValue(parameter, "RequestParam", "required").orElse(true),
                        ParameterLocation.QUERY
                ));
            }
        }
        return parameters;
    }

    private String extractRequestBodyType(MethodDeclaration method) {
        for (Parameter parameter : method.getParameters()) {
            if (hasAnnotation(parameter, "RequestBody")) {
                return parameter.getType().asString();
            }
        }
        return null;
    }

    private String extractResponseType(MethodDeclaration method) {
        String type = method.getType().asString();
        if (type.startsWith("ApiResult<") && type.endsWith(">")) {
            return type.substring("ApiResult<".length(), type.length() - 1);
        }
        return type;
    }

    private Optional<MappingDefinition> extractMethodMapping(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("GetMapping".equals(name)) {
                return Optional.of(new MappingDefinition("GET", extractMappingPath(annotation)));
            }
            if ("PostMapping".equals(name)) {
                return Optional.of(new MappingDefinition("POST", extractMappingPath(annotation)));
            }
            if ("PutMapping".equals(name)) {
                return Optional.of(new MappingDefinition("PUT", extractMappingPath(annotation)));
            }
            if ("DeleteMapping".equals(name)) {
                return Optional.of(new MappingDefinition("DELETE", extractMappingPath(annotation)));
            }
            if ("PatchMapping".equals(name)) {
                return Optional.of(new MappingDefinition("PATCH", extractMappingPath(annotation)));
            }
            if ("RequestMapping".equals(name)) {
                String httpMethod = extractRequestMethod(annotation).orElse("GET");
                return Optional.of(new MappingDefinition(httpMethod, extractMappingPath(annotation)));
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractRequestMethod(AnnotationExpr annotation) {
        return extractNamedMember(annotation, "method")
                .map(Expression::toString)
                .map(raw -> raw.replace("RequestMethod.", "").replace("\"", "").trim())
                .filter(text -> !text.isBlank());
    }

    private Optional<String> extractRequestMappingPath(NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .filter(annotation -> "RequestMapping".equals(annotation.getNameAsString()))
                .findFirst()
                .map(this::extractMappingPath);
    }

    private String extractMappingPath(AnnotationExpr annotation) {
        return extractNamedMember(annotation, "value")
                .or(() -> extractNamedMember(annotation, "path"))
                .map(this::extractStringValue)
                .orElse("");
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
        if (expression.isArrayInitializerExpr()) {
            ArrayInitializerExpr array = expression.asArrayInitializerExpr();
            return array.getValues().getFirst()
                    .map(this::extractStringValue)
                    .orElse("");
        }
        return expression.toString().replace("\"", "");
    }

    private Optional<String> annotationStringValue(Parameter parameter, String annotationName, String attributeName) {
        return parameter.getAnnotations().stream()
                .filter(annotation -> annotationName.equals(annotation.getNameAsString()))
                .findFirst()
                .flatMap(annotation -> extractNamedMember(annotation, attributeName)
                        .or(() -> extractNamedMember(annotation, "name")))
                .map(this::extractStringValue)
                .filter(value -> !value.isBlank());
    }

    private Optional<Boolean> annotationBooleanValue(Parameter parameter, String annotationName, String attributeName) {
        return parameter.getAnnotations().stream()
                .filter(annotation -> annotationName.equals(annotation.getNameAsString()))
                .findFirst()
                .flatMap(annotation -> extractNamedMember(annotation, attributeName))
                .map(Expression::toString)
                .map(value -> Boolean.parseBoolean(value.replace("\"", "")));
    }

    private boolean hasAnnotation(Parameter parameter, String annotationName) {
        return parameter.getAnnotations().stream()
                .anyMatch(annotation -> annotationName.equals(annotation.getNameAsString()));
    }

    private boolean isController(ClassOrInterfaceDeclaration declaration) {
        return declaration.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(name -> "RestController".equals(name) || "Controller".equals(name));
    }

    private List<Path> collectJavaFiles(Path sourcePath) {
        if (Files.isRegularFile(sourcePath)) {
            return shouldIgnorePath(sourcePath) ? List.of() : List.of(sourcePath);
        }

        try (Stream<Path> stream = Files.walk(sourcePath)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !shouldIgnorePath(path))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read source path: " + sourcePath, ex);
        }
    }

    private boolean shouldIgnorePath(Path path) {
        for (Path segment : path) {
            String normalized = segment.toString().toLowerCase(Locale.ROOT);
            if (DEFAULT_IGNORED_PATH_SEGMENTS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private CompilationUnit parse(Path javaFile) {
        try {
            return StaticJavaParser.parse(javaFile);
        } catch (IOException | ParseProblemException ex) {
            throw new IllegalArgumentException("Failed to parse controller source: " + javaFile, ex);
        }
    }

    private boolean isParseFailure(IllegalArgumentException ex) {
        return ex.getMessage() != null && ex.getMessage().startsWith("Failed to parse controller source:");
    }

    private List<ToolDefinition> ensureUniqueToolNames(List<ToolDefinition> tools) {
        Map<String, Integer> counters = new HashMap<>();
        List<ToolDefinition> uniqueTools = new ArrayList<>(tools.size());
        for (ToolDefinition tool : tools) {
            String baseName = tool.name();
            int index = counters.getOrDefault(baseName, 0) + 1;
            counters.put(baseName, index);
            if (index == 1) {
                uniqueTools.add(tool);
                continue;
            }
            uniqueTools.add(new ToolDefinition(
                    baseName + "_" + index,
                    tool.description(),
                    tool.method(),
                    tool.path(),
                    tool.endpoint(),
                    tool.parameters(),
                    tool.requestBodyType(),
                    tool.responseType(),
                    tool.source()
            ));
        }
        return uniqueTools;
    }

    private String normalizeName(String rawName) {
        return rawName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);
    }

    private String mapJavaType(String javaType) {
        return switch (javaType) {
            case "String" -> "string";
            case "int", "Integer", "long", "Long", "short", "Short" -> "integer";
            case "double", "Double", "float", "Float", "BigDecimal" -> "number";
            case "boolean", "Boolean" -> "boolean";
            default -> "string";
        };
    }

    private String resolveToolDescription(MethodDeclaration method) {
        Optional<String> docFromJavaDoc = method.getJavadoc()
                .map(jd -> jd.getDescription().toText().trim())
                .filter(text -> !text.isBlank());
        if (docFromJavaDoc.isPresent()) {
            return docFromJavaDoc.get();
        }
        return extractSwaggerOperationDescription(method)
                .orElse(method.getNameAsString());
    }

    /**
     * Swagger 2 {@code ApiOperation} / OpenAPI 3 {@code Operation} 中的文案，用于无 Javadoc 时的接口说明（如管理端「Ai描述」）。
     */
    private Optional<String> extractSwaggerOperationDescription(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("ApiOperation".equals(name)) {
                Optional<String> text = extractApiOperationDescription(annotation);
                if (text.isPresent()) {
                    return text;
                }
            }
            if ("Operation".equals(name)) {
                Optional<String> text = extractOpenApiOperationDescription(annotation);
                if (text.isPresent()) {
                    return text;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractApiOperationDescription(AnnotationExpr annotation) {
        Optional<String> value = extractNamedMember(annotation, "value")
                .map(this::extractStringValue)
                .map(String::trim)
                .filter(s -> !s.isBlank());
        Optional<String> notes = extractNamedMember(annotation, "notes")
                .map(this::extractStringValue)
                .map(String::trim)
                .filter(s -> !s.isBlank());
        if (value.isPresent() && notes.isPresent() && !notes.get().equals(value.get())) {
            return Optional.of(value.get() + "\n" + notes.get());
        }
        if (value.isPresent()) {
            return value;
        }
        return notes;
    }

    private Optional<String> extractOpenApiOperationDescription(AnnotationExpr annotation) {
        Optional<String> summary = extractNamedMember(annotation, "summary")
                .map(this::extractStringValue)
                .map(String::trim)
                .filter(s -> !s.isBlank());
        Optional<String> description = extractNamedMember(annotation, "description")
                .map(this::extractStringValue)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(this::truncateOperationText);
        if (summary.isPresent() && description.isPresent() && !description.get().equals(summary.get())) {
            return Optional.of(summary.get() + "\n" + description.get());
        }
        return summary.or(() -> description);
    }

    private String truncateOperationText(String text) {
        if (text.length() <= MAX_OPERATION_DESCRIPTION_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OPERATION_DESCRIPTION_CHARS) + "...";
    }

    private String resolveToolName(MethodDeclaration method, String fullPath) {
        String methodName = normalizeName(method.getNameAsString());
        if (GENERIC_METHOD_NAMES.contains(methodName)) {
            return normalizeName(fullPath);
        }
        return methodName;
    }

    private String joinPath(String left, String right) {
        String normalizedLeft = left == null ? "" : left.trim();
        String normalizedRight = right == null ? "" : right.trim();

        if (normalizedLeft.isEmpty()) {
            return normalizedRight.startsWith("/") ? normalizedRight : "/" + normalizedRight;
        }
        if (normalizedRight.isEmpty()) {
            return normalizedLeft.startsWith("/") ? normalizedLeft : "/" + normalizedLeft;
        }

        String leftPart = normalizedLeft.endsWith("/") ? normalizedLeft.substring(0, normalizedLeft.length() - 1) : normalizedLeft;
        String rightPart = normalizedRight.startsWith("/") ? normalizedRight : "/" + normalizedRight;
        return leftPart + rightPart;
    }

    private record MappingDefinition(String httpMethod, String path) {
    }
}
