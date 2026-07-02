package com.enterprise.ai.text.tooling.scanner.controller;

import com.enterprise.ai.text.tooling.scanner.ScanOptions;
import com.enterprise.ai.text.tooling.scanner.manifest.CapabilityMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ParameterLocation;
import com.enterprise.ai.text.tooling.scanner.manifest.ParameterMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolSource;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
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
import java.util.regex.Pattern;

/**
 * 基于 Spring MVC 注解扫描 Controller，生成运行时可消费的扫描结果。
 */
public class ControllerAnnotationToolManifestScanner {

    private static final Logger log = LoggerFactory.getLogger(ControllerAnnotationToolManifestScanner.class);
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

    private static final class ScanState {
        ScanOptions options;
        long incrementalSinceMs;
    }

    private static final ThreadLocal<ScanState> STATE = new ThreadLocal<>();

    public ToolManifest scan(Path sourcePath, ProjectMetadata projectMetadata) {
        return scan(sourcePath, projectMetadata, null, null);
    }

    public ToolManifest scan(Path sourcePath, ProjectMetadata projectMetadata, ScanOptions options, Long incrementalSinceEpochMs) {
        ScanState s = new ScanState();
        s.options = options == null ? ScanOptions.empty() : options;
        s.incrementalSinceMs = incrementalSinceEpochMs == null || incrementalSinceEpochMs < 0 ? 0L : incrementalSinceEpochMs;
        STATE.set(s);
        try {
            // 类索引必须覆盖全仓库源码：增量时若只索引「本次变更的 .java」，则无法解析其它文件里的 VO/DTO，
            // 导致「返回值」RESPONSE 子树为空（接口图谱级联只能选到 VO 根）。
            List<Path> allJavaFiles = collectJavaFiles(sourcePath);
            Map<String, TypeDeclaration<?>> classIndex = buildClassIndex(allJavaFiles);
            List<Path> javaFiles = collectJavaFilesWithIncremental(allJavaFiles, sourcePath, s);
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
        } finally {
            STATE.remove();
        }
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
        ScanState state = currentState();
        List<String> paramOrder = paramDescriptionOrder(state);
        RequestBodySchemaExtractor bodyExtractor = new RequestBodySchemaExtractor(paramOrder);

        for (ClassOrInterfaceDeclaration declaration : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!isController(declaration, state)) {
                continue;
            }
            if (skipByClassFqn(declaration, state)) {
                continue;
            }
            if (isClassDeprecated(declaration) && isSkipDeprecated(state)) {
                continue;
            }
            String basePath = extractRequestMappingPath(declaration.getAnnotations()).orElse("");
            for (MethodDeclaration method : declaration.getMethods()) {
                if (isMethodDeprecated(method) && isSkipDeprecated(state)) {
                    continue;
                }
                Optional<MappingDefinition> mapping = extractMethodMapping(method, state);
                if (mapping.isEmpty()) {
                    continue;
                }

                MappingDefinition definition = mapping.get();
                List<ToolParameterDefinition> parameters = extractParameters(method, classIndex, bodyExtractor, state);
                parameters = appendResolvedResponseParameter(parameters, extractResponseType(method), bodyExtractor, classIndex);
                String requestBodyType = extractRequestBodyType(method);
                Optional<AnnotationExpr> aiCapability = findAnnotation(method.getAnnotations(), "AiCapability");
                CapabilityMetadata capabilityMetadata = aiCapability
                        .map(this::extractCapabilityMetadata)
                        .orElse(null);

                tools.add(new ToolDefinition(
                        resolveToolName(method, joinPath(basePath, definition.path()), aiCapability),
                        resolveToolDescription(method, state, aiCapability),
                        definition.httpMethod(),
                        joinPath(basePath, definition.path()),
                        definition.httpMethod() + " " + joinPath(projectMetadata.contextPath(), joinPath(basePath, definition.path())),
                        parameters,
                        requestBodyType,
                        extractResponseType(method),
                        new ToolSource(
                                "controller",
                                javaFile.getFileName() + "#" + declaration.getNameAsString() + "#" + method.getNameAsString()
                        ),
                        capabilityMetadata
                ));
            }
        }

        return tools;
    }

    private static ScanState currentState() {
        ScanState s = STATE.get();
        if (s == null) {
            s = new ScanState();
            s.options = ScanOptions.empty();
        }
        return s;
    }

    private static boolean isSkipDeprecated(ScanState s) {
        return Boolean.TRUE.equals(s.options.getSkipDeprecated());
    }

    private static List<String> paramDescriptionOrder(ScanState s) {
        List<String> p = s.options.getParamDescriptionSourceOrder();
        if (p == null || p.isEmpty()) {
            p = List.of(ScanOptions.PS_PARAM, ScanOptions.PS_SCHEMA, ScanOptions.PS_JD, ScanOptions.PS_FIELD);
        }
        return applySourceEnabled(p, s.options.getParamDescriptionSourceEnabled());
    }

    private static List<String> descriptionOrder(ScanState s) {
        List<String> p = s.options.getDescriptionSourceOrder();
        if (p == null || p.isEmpty()) {
            p = List.of(ScanOptions.SRC_SWAGGER_API, ScanOptions.SRC_OPENAPI_OP,
                    ScanOptions.SRC_JAVADOC, ScanOptions.SRC_METHOD_NAME);
        }
        return applySourceEnabled(p, s.options.getDescriptionSourceEnabled());
    }

    /**
     * 为 false 的项从优先级中移除；未出现在 map 中的 key 仍视为开启（兼容旧配置）。
     */
    private static List<String> applySourceEnabled(List<String> order, Map<String, Boolean> enabled) {
        if (order == null || order.isEmpty() || enabled == null || enabled.isEmpty()) {
            return order;
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

    private boolean isClassDeprecated(ClassOrInterfaceDeclaration declaration) {
        return declaration.getAnnotations().stream().anyMatch(a -> "Deprecated".equals(a.getNameAsString()));
    }

    private boolean isMethodDeprecated(MethodDeclaration method) {
        if (method.getAnnotations().stream().anyMatch(a -> "Deprecated".equals(a.getNameAsString()))) {
            return true;
        }
        return method.getJavadoc().map(this::javadocHasDeprecated).orElse(false);
    }

    private boolean javadocHasDeprecated(Javadoc javadoc) {
        for (JavadocBlockTag t : javadoc.getBlockTags()) {
            if ("deprecated".equalsIgnoreCase(t.getTagName())) {
                return true;
            }
        }
        return false;
    }

    private boolean skipByClassFqn(ClassOrInterfaceDeclaration declaration, ScanState state) {
        String fqn = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        String include = state.options.getClassIncludeRegex();
        if (include != null && !include.isBlank()) {
            if (!Pattern.compile(include, Pattern.CASE_INSENSITIVE).matcher(fqn).find()) {
                return true;
            }
        }
        String exclude = state.options.getClassExcludeRegex();
        if (exclude != null && !exclude.isBlank()) {
            if (Pattern.compile(exclude, Pattern.CASE_INSENSITIVE).matcher(fqn).find()) {
                return true;
            }
        }
        return false;
    }

    private List<ToolParameterDefinition> extractParameters(MethodDeclaration method,
                                                            Map<String, TypeDeclaration<?>> classIndex,
                                                            RequestBodySchemaExtractor bodySchemaExtractor,
                                                            ScanState state) {
        List<ToolParameterDefinition> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (hasAnnotation(parameter, "RequestBody")) {
                String rawType = parameter.getType().asString();
                List<ToolParameterDefinition> children = bodySchemaExtractor.extract(rawType, classIndex);
                parameters.add(new ToolParameterDefinition(
                        "body_json",
                        "json",
                        aiParamDescription(parameter).orElse("JSON 请求体，对应 " + rawType),
                        aiParamRequired(parameter).orElse(annotationBooleanValue(parameter, "RequestBody", "required").orElse(true)),
                        ParameterLocation.BODY,
                        children,
                        extractAiParamMetadata(parameter).orElse(null)
                ));
                continue;
            }

            if (hasAnnotation(parameter, "PathVariable")) {
                String desc = resolveParameterText(parameter, method, state, parameter.getNameAsString());
                parameters.add(new ToolParameterDefinition(
                        annotationStringValue(parameter, "PathVariable", "value").orElse(parameter.getNameAsString()),
                        mapJavaType(parameter.getType().asString()),
                        desc,
                        true,
                        ParameterLocation.PATH
                ));
                continue;
            }

            if (hasAnnotation(parameter, "RequestParam")) {
                String desc = resolveParameterText(parameter, method, state, parameter.getNameAsString());
                parameters.add(new ToolParameterDefinition(
                        annotationStringValue(parameter, "RequestParam", "value").orElse(parameter.getNameAsString()),
                        mapJavaType(parameter.getType().asString()),
                        desc,
                        annotationBooleanValue(parameter, "RequestParam", "required").orElse(true),
                        ParameterLocation.QUERY
                ));
            }
        }
        return parameters;
    }

    /**
     * 当方法返回类型可解析为源码中的 DTO/VO 并展开出字段时，追加 {@code location=RESPONSE} 的「返回值」参数树，
     * 使下游接口图谱能选到 VO 内字段，而不仅是根类型节点。
     */
    private List<ToolParameterDefinition> appendResolvedResponseParameter(
            List<ToolParameterDefinition> parameters,
            String responseType,
            RequestBodySchemaExtractor bodyExtractor,
            Map<String, TypeDeclaration<?>> classIndex) {
        if (responseType == null || responseType.isBlank()) {
            return parameters;
        }
        String trimmed = responseType.trim();
        if ("void".equalsIgnoreCase(trimmed)) {
            return parameters;
        }
        List<ToolParameterDefinition> responseChildren =
                bodyExtractor.extract(trimmed, classIndex, ParameterLocation.RESPONSE);
        if (responseChildren.isEmpty()) {
            if (log.isDebugEnabled() && looksLikeUnresolvedDtoName(trimmed)) {
                log.debug("Controller scan: skip RESPONSE schema for return type '{}' (no resolvable fields in index; e.g. VO in another module, Lombok-only, or primitive wrapper)",
                        trimmed);
            }
            return parameters;
        }
        if (log.isDebugEnabled()) {
            log.debug("Controller scan: append RESPONSE schema for return type '{}' ({} top-level fields)",
                    trimmed, responseChildren.size());
        }
        List<ToolParameterDefinition> out = new ArrayList<>(parameters);
        out.add(new ToolParameterDefinition(
                "返回值",
                trimmed,
                "由控制器方法返回类型解析的响应体结构",
                false,
                ParameterLocation.RESPONSE,
                responseChildren
        ));
        return out;
    }

    /** 避免对 string、void 等打无意义 debug */
    private static boolean looksLikeUnresolvedDtoName(String trimmed) {
        if (trimmed == null || trimmed.isBlank()) {
            return false;
        }
        String t = trimmed.trim();
        if ("void".equalsIgnoreCase(t)) {
            return false;
        }
        char c = t.charAt(0);
        return Character.isUpperCase(c);
    }

    private String resolveParameterText(Parameter param, MethodDeclaration method, ScanState s, String fallback) {
        Optional<String> aiParam = aiParamDescription(param);
        if (aiParam.isPresent()) {
            return aiParam.get();
        }
        for (String key : paramDescriptionOrder(s)) {
            if (key == null) {
                continue;
            }
            String u = key.trim();
            if (ScanOptions.PS_JD.equals(u) || "JAVADOC_PARAM".equals(u)) {
                String name = param.getNameAsString();
                Optional<String> p = javadocParamLine(method, name);
                if (p.isPresent() && !p.get().isBlank()) {
                    return p.get();
                }
            } else if (ScanOptions.PS_SCHEMA.equals(u) || "SCHEMA_ANNO".equals(u)) {
                for (AnnotationExpr a : param.getAnnotations()) {
                    if ("Schema".equals(a.getNameAsString())) {
                        Optional<String> t = extractNamedMember(a, "description").map(this::extractStringValue);
                        if (t.isPresent() && !t.get().isBlank()) {
                            return t.get();
                        }
                    }
                }
            } else if (ScanOptions.PS_PARAM.equals(u) || "PARAMETER_ANNO".equals(u)) {
                for (AnnotationExpr a : param.getAnnotations()) {
                    if ("Parameter".equals(a.getNameAsString())) {
                        Optional<String> t = extractNamedMember(a, "description")
                                .or(() -> extractNamedMember(a, "name"))
                                .map(this::extractStringValue);
                        if (t.isPresent() && !t.get().isBlank()) {
                            return t.get();
                        }
                    }
                }
            } else if (ScanOptions.PS_FIELD.equals(u) || "FIELD_NAME".equals(u)) {
                return fallback;
            }
        }
        return fallback;
    }

    private Optional<String> javadocParamLine(MethodDeclaration method, String paramName) {
        if (method.getJavadoc().isEmpty()) {
            return Optional.empty();
        }
        for (JavadocBlockTag t : method.getJavadoc().get().getBlockTags()) {
            if (!"param".equalsIgnoreCase(t.getTagName())) {
                continue;
            }
            if (t.getName().isEmpty()) {
                continue;
            }
            // 不同 JavaParser 版本里 getName 可能为 SimpleName 等，避免直接 asString
            String pName = t.getName().map(Object::toString).orElse("").trim();
            if (pName.isEmpty() || !paramName.equals(pName)) {
                continue;
            }
            return Optional.of(t.getContent().toText().trim()).filter(s -> !s.isBlank());
        }
        return Optional.empty();
    }

    private String extractRequestBodyType(MethodDeclaration method) {
        for (Parameter parameter : method.getParameters()) {
            if (hasAnnotation(parameter, "RequestBody")) {
                return parameter.getType().asString();
            }
        }
        return null;
    }

    private static final Pattern GENERIC_WRAPPER_PATTERN = Pattern.compile("^\\s*([A-Z]\\w*)\\s*<(.+)>\\s*$");

    /** 常见 HTTP 响应包装类型前缀；匹配时会剥掉外层泛型，只保留内部类型参数。 */
    private static final Set<String> RESPONSE_WRAPPER_PREFIXES = Set.of(
            "apiresult", "webapiresult", "apiresponse", "result",
            "responseentity", "response", "basresult", "commonresult",
            "restult", "ajaxresult", "jsonresult", "httpentity"
    );

    private String extractResponseType(MethodDeclaration method) {
        String type = method.getType().asString();
        // 处理常见泛型包装：ApiResult<T>、WebApiResult<T>、ResponseEntity<T> 等
        java.util.regex.Matcher m = GENERIC_WRAPPER_PATTERN.matcher(type);
        if (m.matches()) {
            String wrapperName = m.group(1).toLowerCase(Locale.ROOT);
            if (RESPONSE_WRAPPER_PREFIXES.contains(wrapperName)) {
                return m.group(2).trim();
            }
        }
        return type;
    }

    private Optional<MappingDefinition> extractMethodMapping(MethodDeclaration method, ScanState state) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("GetMapping".equals(name)) {
                return filterByHttpWhitelist(new MappingDefinition("GET", extractMappingPath(annotation)), state);
            }
            if ("PostMapping".equals(name)) {
                return filterByHttpWhitelist(new MappingDefinition("POST", extractMappingPath(annotation)), state);
            }
            if ("PutMapping".equals(name)) {
                return filterByHttpWhitelist(new MappingDefinition("PUT", extractMappingPath(annotation)), state);
            }
            if ("DeleteMapping".equals(name)) {
                return filterByHttpWhitelist(new MappingDefinition("DELETE", extractMappingPath(annotation)), state);
            }
            if ("PatchMapping".equals(name)) {
                return filterByHttpWhitelist(new MappingDefinition("PATCH", extractMappingPath(annotation)), state);
            }
            if ("RequestMapping".equals(name)) {
                String httpMethod = extractRequestMethod(annotation).orElse("GET");
                return filterByHttpWhitelist(new MappingDefinition(httpMethod, extractMappingPath(annotation)), state);
            }
        }
        return Optional.empty();
    }

    private Optional<MappingDefinition> filterByHttpWhitelist(MappingDefinition def, ScanState state) {
        if (def == null) {
            return Optional.empty();
        }
        if (!httpMethodAllowed(def.httpMethod(), state)) {
            return Optional.empty();
        }
        return Optional.of(def);
    }

    private static boolean httpMethodAllowed(String m, ScanState s) {
        if (m == null) {
            return false;
        }
        if (s.options.getHttpMethodWhitelist() == null || s.options.getHttpMethodWhitelist().isEmpty()) {
            return true;
        }
        for (String w : s.options.getHttpMethodWhitelist()) {
            if (m.equalsIgnoreCase(w == null ? "" : w.trim())) {
                return true;
            }
        }
        return false;
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

    private Optional<AnnotationExpr> findAnnotation(NodeList<AnnotationExpr> annotations, String annotationName) {
        return annotations.stream()
                .filter(annotation -> annotationName.equals(annotation.getNameAsString()))
                .findFirst();
    }

    private CapabilityMetadata extractCapabilityMetadata(AnnotationExpr annotation) {
        List<String> tags = extractStringArray(annotation, "tags");
        List<String> roles = extractStringArray(annotation, "requiredRoles");
        Integer timeoutMs = extractIntMember(annotation, "timeoutMs").filter(v -> v > 0).orElse(null);
        Integer retryLimit = extractIntMember(annotation, "retryLimit").filter(v -> v >= 0).orElse(null);
        return new CapabilityMetadata(
                true,
                extractNamedMember(annotation, "name").map(this::extractStringValue).filter(s -> !s.isBlank()).orElse(null),
                extractNamedMember(annotation, "title").map(this::extractStringValue).filter(s -> !s.isBlank()).orElse(null),
                extractNamedMember(annotation, "domain").map(this::extractStringValue).filter(s -> !s.isBlank()).orElse(null),
                extractNamedMember(annotation, "module").map(this::extractStringValue).filter(s -> !s.isBlank()).orElse(null),
                tags,
                extractNamedMember(annotation, "sideEffect").map(this::extractEnumName).filter(s -> !s.isBlank()).orElse(null),
                extractBooleanMember(annotation, "agentVisible").orElse(null),
                roles,
                timeoutMs,
                retryLimit,
                "AiCapability"
        );
    }

    private Optional<String> aiParamDescription(Parameter parameter) {
        return findAnnotation(parameter.getAnnotations(), "AiParam")
                .flatMap(annotation -> extractNamedMember(annotation, "description"))
                .map(this::extractStringValue)
                .filter(value -> !value.isBlank());
    }

    private Optional<Boolean> aiParamRequired(Parameter parameter) {
        return findAnnotation(parameter.getAnnotations(), "AiParam")
                .flatMap(annotation -> extractBooleanMember(annotation, "required"))
                .filter(Boolean::booleanValue);
    }

    private Optional<ParameterMetadata> extractAiParamMetadata(Parameter parameter) {
        return findAnnotation(parameter.getAnnotations(), "AiParam").map(annotation -> new ParameterMetadata(
                extractNamedMember(annotation, "example").map(this::extractStringValue).filter(s -> !s.isBlank()).orElse(null),
                extractNamedMember(annotation, "sourceHint").map(this::extractStringValue).filter(s -> !s.isBlank()).orElse(null),
                extractNamedMember(annotation, "dictType").map(this::extractStringValue).filter(s -> !s.isBlank()).orElse(null),
                extractBooleanMember(annotation, "sensitive").orElse(null),
                null,
                List.of(),
                "AiParam"
        ));
    }

    private Optional<Boolean> extractBooleanMember(AnnotationExpr annotation, String name) {
        return extractNamedMember(annotation, name)
                .map(Expression::toString)
                .map(raw -> raw.replace("\"", "").trim().toLowerCase(Locale.ROOT))
                .filter(raw -> "true".equals(raw) || "false".equals(raw))
                .map(Boolean::parseBoolean);
    }

    private Optional<Integer> extractIntMember(AnnotationExpr annotation, String name) {
        return extractNamedMember(annotation, name)
                .map(Expression::toString)
                .map(raw -> raw.replace("\"", "").trim())
                .flatMap(raw -> {
                    try {
                        return Optional.of(Integer.parseInt(raw));
                    } catch (NumberFormatException ex) {
                        return Optional.empty();
                    }
                });
    }

    private List<String> extractStringArray(AnnotationExpr annotation, String name) {
        Optional<Expression> expr = extractNamedMember(annotation, name);
        if (expr.isEmpty()) {
            return List.of();
        }
        Expression value = expr.get();
        if (value.isArrayInitializerExpr()) {
            return value.asArrayInitializerExpr().getValues().stream()
                    .map(this::extractStringValue)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        String single = extractStringValue(value).trim();
        return single.isBlank() ? List.of() : List.of(single);
    }

    private String extractEnumName(Expression expression) {
        String raw = expression.toString().replace("\"", "").trim();
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private boolean hasAnnotation(Parameter parameter, String annotationName) {
        return parameter.getAnnotations().stream()
                .anyMatch(annotation -> annotationName.equals(annotation.getNameAsString()));
    }

    private boolean isController(ClassOrInterfaceDeclaration declaration, ScanState s) {
        if (s.options.getOnlyRestController() == null || Boolean.TRUE.equals(s.options.getOnlyRestController())) {
            return declaration.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .anyMatch("RestController"::equals);
        }
        return declaration.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(name -> "RestController".equals(name) || "Controller".equals(name));
    }

    /**
     * @param allJavaFiles {@link #collectJavaFiles} 的完整列表（避免重复 walk）
     */
    private List<Path> collectJavaFilesWithIncremental(List<Path> allJavaFiles, Path sourcePath, ScanState s) {
        if (s.incrementalSinceMs > 0) {
            return IncrementalFileFilter.apply(allJavaFiles, sourcePath, s.incrementalSinceMs, s.options);
        }
        return allJavaFiles;
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
                    tool.source(),
                    tool.capabilityMetadata()
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

    private String resolveToolDescription(MethodDeclaration method, ScanState s, Optional<AnnotationExpr> aiCapability) {
        Optional<String> declared = aiCapability
                .flatMap(annotation -> extractNamedMember(annotation, "description"))
                .map(this::extractStringValue)
                .map(String::trim)
                .filter(text -> !text.isBlank());
        if (declared.isPresent()) {
            return declared.get();
        }
        for (String key : descriptionOrder(s)) {
            if (key == null) {
                continue;
            }
            String u = key.trim();
            if (ScanOptions.SRC_JAVADOC.equals(u) || "JAVADOC".equals(u)) {
                Optional<String> doc = method.getJavadoc()
                        .map(jd -> jd.getDescription().toText().trim())
                        .filter(text -> !text.isBlank());
                if (doc.isPresent()) {
                    return doc.get();
                }
            } else if (ScanOptions.SRC_SWAGGER_API.equals(u) || "SWAGGER_API_OPERATION".equals(u)) {
                Optional<String> op = extractSwaggerApiOperationText(method);
                if (op.isPresent()) {
                    return op.get();
                }
            } else if (ScanOptions.SRC_OPENAPI_OP.equals(u) || "OPENAPI_OPERATION".equals(u)) {
                Optional<String> op2 = extractOpenApiOperationText(method);
                if (op2.isPresent()) {
                    return op2.get();
                }
            } else if (ScanOptions.SRC_METHOD_NAME.equals(u) || "METHOD_NAME".equals(u)) {
                return method.getNameAsString();
            }
        }
        return method.getNameAsString();
    }

    private Optional<String> extractSwaggerApiOperationText(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if ("ApiOperation".equals(annotation.getNameAsString())) {
                return extractApiOperationDescription(annotation);
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractOpenApiOperationText(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if ("Operation".equals(annotation.getNameAsString())) {
                return extractOpenApiOperationDescription(annotation);
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

    private String resolveToolName(MethodDeclaration method, String fullPath, Optional<AnnotationExpr> aiCapability) {
        Optional<String> declared = aiCapability
                .flatMap(annotation -> extractNamedMember(annotation, "name"))
                .map(this::extractStringValue)
                .map(this::normalizeName)
                .filter(name -> !name.isBlank());
        if (declared.isPresent()) {
            return declared.get();
        }
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
