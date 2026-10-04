package com.enterprise.ai.text.tooling.scanner.controller;

import com.enterprise.ai.text.tooling.scanner.ScanOptions;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Extracts a complete-or-explicitly-partial HTTP API inventory from static Spring MVC source.
 *
 * <p>The legacy scanner remains responsible for its Tool manifest. This extractor intentionally
 * refuses to make up an HTTP contract when a mapping, binding location, or sensitive mapping
 * predicate cannot be represented safely. In that case it omits the operation and marks the
 * inventory incomplete, which prevents Capability from treating the response as a deletion list.</p>
 */
final class ControllerHttpApiInventoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(ControllerHttpApiInventoryExtractor.class);
    private static final List<String> ALL_HTTP_METHODS = List.of(
            "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE");
    private static final Set<String> HTTP_METHODS = Set.copyOf(ALL_HTTP_METHODS);
    private static final Set<String> RESPONSE_WRAPPERS = Set.of(
            "apiresult", "webapiresult", "apiresponse", "result", "responseentity", "response",
            "basresult", "commonresult", "restult", "ajaxresult", "jsonresult", "httpentity", "optional");
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "list", "set", "collection", "iterable", "queue", "deque", "stream");
    private static final Set<String> MAP_TYPES = Set.of("map", "hashmap", "linkedhashmap", "treemap");
    private static final Set<String> STRING_TYPES = Set.of(
            "string", "charsequence", "char", "character", "uuid", "uri", "url",
            "localdate", "localdatetime", "localtime", "offsetdatetime", "zoneddatetime", "instant", "date", "timestamp");
    private static final Set<String> INTEGER_TYPES = Set.of(
            "byte", "short", "int", "integer", "long", "biginteger");
    private static final Set<String> NUMBER_TYPES = Set.of(
            "float", "double", "bigdecimal", "number");
    private static final Set<String> REQUIRED_FIELD_ANNOTATIONS = Set.of("NotNull", "NotBlank", "NotEmpty");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:base[_-]?url|credentialref|password|token|secret|api[_-]?key)\\s*(?:=|:)|"
                    + "authorization\\s*(?:=|:)\\s*bearer|cookie\\s*(?:=|:)");

    Result extract(Path scanRoot,
                   List<Path> allJavaFiles,
                   List<Path> selectedJavaFiles,
                   ProjectMetadata metadata,
                   Map<String, TypeDeclaration<?>> classIndex,
                   ScanOptions options) {
        boolean complete = selectedJavaFiles.size() == allJavaFiles.size() && !hasFilteringScope(options);
        List<HttpApiOperation> operations = new ArrayList<>();
        for (Path javaFile : selectedJavaFiles) {
            try {
                FileResult result = scanFile(scanRoot, javaFile, metadata, classIndex, options);
                operations.addAll(result.operations());
                complete &= result.complete();
            } catch (RuntimeException failure) {
                // Do not echo a parser expression or source content: either may contain a secret literal.
                log.warn("Skip incomplete Controller HTTP API inventory for source {}: {}",
                        displayPath(scanRoot, javaFile), failure.getClass().getSimpleName());
                complete = false;
            }
        }
        operations.sort(Comparator.comparing(HttpApiOperation::sourceKey));
        Set<String> sourceKeys = new HashSet<>();
        for (HttpApiOperation operation : operations) {
            if (!sourceKeys.add(operation.sourceKey())) {
                throw new IllegalArgumentException("Controller HTTP API inventory has duplicate source identity");
            }
        }
        return new Result(List.copyOf(operations), complete);
    }

    private FileResult scanFile(Path scanRoot,
                                Path javaFile,
                                ProjectMetadata metadata,
                                Map<String, TypeDeclaration<?>> classIndex,
                                ScanOptions options) {
        CompilationUnit unit;
        try {
            unit = StaticJavaParser.parse(javaFile);
        } catch (Exception failure) {
            if (failure instanceof ParseProblemException || failure.getCause() instanceof ParseProblemException) {
                return new FileResult(List.of(), false);
            }
            throw new IllegalArgumentException("Controller source cannot be parsed", failure);
        }
        List<HttpApiOperation> result = new ArrayList<>();
        boolean complete = true;
        for (ClassOrInterfaceDeclaration declaration : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!isController(declaration, options) || skipByClassFqn(declaration, options)
                    || (isDeprecated(declaration.getAnnotations()) && skipDeprecated(options))) {
                continue;
            }
            MappingParse classMapping = parseClassMapping(declaration.getAnnotations(), classIndex);
            if (!classMapping.supported()) {
                complete = false;
                continue;
            }
            for (MethodDeclaration method : declaration.getMethods()) {
                if (isDeprecated(method.getAnnotations()) && skipDeprecated(options)) {
                    continue;
                }
                MappingParse methodMapping = parseMethodMapping(method.getAnnotations(), classIndex);
                if (!methodMapping.present()) {
                    // An external annotation may compose a Spring route even when its name is
                    // opaque. Absence is trustworthy only when its meta-annotation graph is known.
                    if (hasUnresolvedAnnotation(method.getAnnotations(), classIndex, new HashSet<>())) {
                        complete = false;
                    }
                    continue;
                }
                if (!methodMapping.supported()) {
                    complete = false;
                    continue;
                }
                ParameterParse parameters = parseParameters(method, classIndex, effectiveMedia(classMapping.mapping(), methodMapping.mapping(), true));
                StatusParse status = responseStatus(declaration, method);
                if (!parameters.supported() || !status.supported()) {
                    complete = false;
                    continue;
                }
                for (OperationVariant variant : combine(classMapping.mapping(), methodMapping.mapping())) {
                    if (!httpMethodAllowed(variant.method(), options)) {
                        continue;
                    }
                    String sourceLocation = sourceLocation(scanRoot, javaFile, declaration, method);
                    String sourceKey = sourceKey(scanRoot, javaFile, declaration, method, variant);
                    HttpApiOperation operation = new HttpApiOperation(
                            sourceKey,
                            sourceLocation,
                            null,
                            variant.method(),
                            contextPath(metadata.contextPath()),
                            variant.endpointPath(),
                            variant.consumes(),
                            variant.produces(),
                            variant.conditions(),
                            parameters.parameters(),
                            parameters.requestBody(),
                            List.of(new HttpApiOperation.Response(
                                    status.status(), schema(responseBodyType(method.getType().asString()), classIndex,
                                    new HashSet<>(), 0), variant.produces())),
                            "UNKNOWN",
                            List.of(),
                            List.of(),
                            readOnly(variant.method()) ? "READ_ONLY" : "WRITE");
                    result.add(withRevision(operation));
                }
            }
        }
        return new FileResult(result, complete);
    }

    private MappingParse parseClassMapping(NodeList<AnnotationExpr> annotations,
                                           Map<String, TypeDeclaration<?>> classIndex) {
        MappingParse parsed = parseSingleMapping(annotations, classIndex);
        if (!parsed.present() && hasUnresolvedAnnotation(annotations, classIndex, new HashSet<>())) {
            return MappingParse.unsupported();
        }
        return parsed.present() ? parsed : MappingParse.supported(Mapping.empty());
    }

    private MappingParse parseMethodMapping(NodeList<AnnotationExpr> annotations,
                                            Map<String, TypeDeclaration<?>> classIndex) {
        return parseSingleMapping(annotations, classIndex);
    }

    private MappingParse parseSingleMapping(NodeList<AnnotationExpr> annotations,
                                            Map<String, TypeDeclaration<?>> classIndex) {
        List<AnnotationExpr> mappingIntents = annotations.stream()
                .filter(annotation -> hasHttpMappingIntent(annotation, classIndex, new HashSet<>())).toList();
        if (mappingIntents.isEmpty()) {
            return MappingParse.absent();
        }
        List<AnnotationExpr> mappings = mappingIntents.stream().filter(this::isMappingAnnotation).toList();
        // A composed mapping can have Spring MVC semantics without exposing literals this scanner
        // can safely expand. Treat it as incomplete instead of silently reporting an empty route.
        if (mappingIntents.size() != 1 || mappings.size() != 1) {
            return MappingParse.unsupported();
        }
        AnnotationExpr annotation = mappings.get(0);
        String name = simpleName(annotation.getNameAsString());
        LiteralList paths = aliasStrings(annotation, "path", "value");
        LiteralList consumes = strings(annotation, "consumes");
        LiteralList produces = strings(annotation, "produces");
        LiteralList headers = strings(annotation, "headers");
        LiteralList params = strings(annotation, "params");
        if (!paths.supported() || !consumes.supported() || !produces.supported()
                || !headers.supported() || !params.supported()
                || !validPaths(paths.values()) || !validMedia(consumes.values()) || !validMedia(produces.values())) {
            return MappingParse.unsupported();
        }
        MethodList methods;
        if ("RequestMapping".equals(name)) {
            methods = methods(annotation);
            if (!methods.supported()) {
                return MappingParse.unsupported();
            }
        } else {
            methods = MethodList.supported(List.of(name.substring(0, name.length() - "Mapping".length()).toUpperCase(Locale.ROOT)));
        }
        HeaderParse headerFacts = headerFacts(headers.values());
        if (!headerFacts.supported()) {
            return MappingParse.unsupported();
        }
        List<HttpApiOperation.MappingCondition> conditions = new ArrayList<>(headerFacts.conditions());
        if (!addConditions(conditions, "PARAM", params.values())) {
            return MappingParse.unsupported();
        }
        List<String> declaredConsumes = mergeMedia(consumes.values(), headerFacts.consumes());
        List<String> declaredProduces = mergeMedia(produces.values(), headerFacts.produces());
        return MappingParse.supported(new Mapping(
                normalizedPaths(paths.present() ? paths.values() : List.of("")),
                methods.values(),
                declaredConsumes, consumes.present() || !headerFacts.consumes().isEmpty(),
                declaredProduces, produces.present() || !headerFacts.produces().isEmpty(),
                sortedConditions(conditions)));
    }

    private boolean isMappingAnnotation(AnnotationExpr annotation) {
        String name = simpleName(annotation.getNameAsString());
        return Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping").contains(name);
    }

    /**
     * Detect direct Spring mappings, mapping-like names, and source-local composed annotations.
     * Unknown meta-annotation graphs are checked separately before absence is considered complete.
     */
    private boolean hasHttpMappingIntent(AnnotationExpr annotation,
                                         Map<String, TypeDeclaration<?>> classIndex,
                                         Set<String> visiting) {
        if (isMappingAnnotation(annotation)) {
            return true;
        }
        String name = simpleName(annotation.getNameAsString()).toLowerCase(Locale.ROOT);
        if (name.endsWith("requestmapping") || name.endsWith("getmapping") || name.endsWith("postmapping")
                || name.endsWith("putmapping") || name.endsWith("deletemapping") || name.endsWith("patchmapping")) {
            return true;
        }
        TypeDeclaration<?> declaration = classIndex.get(annotation.getNameAsString());
        if (declaration == null) {
            declaration = classIndex.get(simpleName(annotation.getNameAsString()));
        }
        if (!(declaration instanceof AnnotationDeclaration)) {
            return false;
        }
        String identity = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        if (!visiting.add(identity)) {
            return false;
        }
        return declaration.getAnnotations().stream()
                .anyMatch(meta -> hasHttpMappingIntent(meta, classIndex, visiting));
    }

    private boolean hasUnresolvedAnnotation(NodeList<AnnotationExpr> annotations,
                                             Map<String, TypeDeclaration<?>> classIndex,
                                             Set<String> visiting) {
        for (AnnotationExpr annotation : annotations) {
            String name = simpleName(annotation.getNameAsString());
            // Standard non-mapping annotations have a known meaning; custom annotations do not.
            if (Set.of("RestController", "Controller", "ResponseBody", "ResponseStatus", "ExceptionHandler",
                    "InitBinder", "ModelAttribute", "Override", "Deprecated", "SuppressWarnings", "SafeVarargs",
                    "Target", "Retention", "Documented", "Inherited", "Repeatable").contains(name)) continue;
            TypeDeclaration<?> declaration = classIndex.get(annotation.getNameAsString());
            if (declaration == null) declaration = classIndex.get(name);
            if (!(declaration instanceof AnnotationDeclaration)) return true;
            String identity = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
            if (!visiting.add(identity)) return true;
            boolean unresolved = hasUnresolvedAnnotation(declaration.getAnnotations(), classIndex, visiting);
            visiting.remove(identity);
            if (unresolved) return true;
        }
        return false;
    }

    private MethodList methods(AnnotationExpr annotation) {
        Optional<Expression> raw = member(annotation, "method");
        if (raw.isEmpty()) {
            return MethodList.supported(List.of());
        }
        List<Expression> values = expressions(raw.get());
        if (values == null) {
            return MethodList.unsupported();
        }
        List<String> result = new ArrayList<>();
        for (Expression expression : values) {
            String method = staticHttpMethod(expression);
            if (method == null) {
                return MethodList.unsupported();
            }
            result.add(method);
        }
        return MethodList.supported(result.stream().distinct().sorted().toList());
    }

    private String staticHttpMethod(Expression expression) {
        String candidate;
        if (expression.isFieldAccessExpr()) {
            candidate = expression.asFieldAccessExpr().getNameAsString();
        } else if (expression.isNameExpr()) {
            candidate = expression.asNameExpr().getNameAsString();
        } else if (expression.isStringLiteralExpr()) {
            candidate = expression.asStringLiteralExpr().asString();
        } else {
            return null;
        }
        candidate = candidate.trim().toUpperCase(Locale.ROOT);
        return HTTP_METHODS.contains(candidate) ? candidate : null;
    }

    private List<OperationVariant> combine(Mapping parent, Mapping child) {
        List<String> methods = union(parent.methods(), child.methods());
        if (methods.isEmpty()) {
            methods = ALL_HTTP_METHODS;
        }
        List<String> consumes = effectiveMedia(parent, child, true);
        List<String> produces = effectiveMedia(parent, child, false);
        List<HttpApiOperation.MappingCondition> conditions = new ArrayList<>(parent.conditions());
        conditions.addAll(child.conditions());
        conditions = sortedConditions(conditions);
        List<OperationVariant> result = new ArrayList<>();
        for (String classPath : parent.paths()) {
            for (String methodPath : child.paths()) {
                String endpointPath = joinPath(classPath, methodPath);
                for (String method : methods) {
                    result.add(new OperationVariant(method, endpointPath, consumes, produces, conditions));
                }
            }
        }
        return result;
    }

    private List<String> effectiveMedia(Mapping parent, Mapping child, boolean consumes) {
        List<String> childValues = consumes ? child.consumes() : child.produces();
        boolean childDeclared = consumes ? child.consumesDeclared() : child.producesDeclared();
        return childDeclared && !childValues.isEmpty()
                ? childValues
                : (consumes ? parent.consumes() : parent.produces());
    }

    private ParameterParse parseParameters(MethodDeclaration method,
                                           Map<String, TypeDeclaration<?>> classIndex,
                                           List<String> consumes) {
        List<HttpApiOperation.Parameter> parameters = new ArrayList<>();
        HttpApiOperation.RequestBody body = null;
        for (Parameter parameter : method.getParameters()) {
            List<AnnotationExpr> bindings = parameter.getAnnotations().stream()
                    .filter(annotation -> Set.of("PathVariable", "RequestParam", "RequestHeader", "CookieValue", "RequestBody")
                            .contains(simpleName(annotation.getNameAsString())))
                    .toList();
            if (bindings.size() != 1) {
                return ParameterParse.unsupported();
            }
            AnnotationExpr binding = bindings.get(0);
            String kind = simpleName(binding.getNameAsString());
            boolean optional = optionalType(parameter.getType().asString());
            if ("RequestBody".equals(kind)) {
                if (body != null) {
                    return ParameterParse.unsupported();
                }
                BooleanParse required = booleanAttribute(binding, "required", true);
                if (!required.supported()) {
                    return ParameterParse.unsupported();
                }
                body = new HttpApiOperation.RequestBody("BODY", required.value() && !optional,
                        schema(parameter.getType().asString(), classIndex, new HashSet<>(), 0), consumes);
                continue;
            }
            NameParse name = bindingName(binding, parameter);
            BooleanParse required = booleanAttribute(binding, "required", true);
            if (!name.supported() || !required.supported()) {
                return ParameterParse.unsupported();
            }
            boolean hasDefault = hasNonSentinelDefault(binding);
            String location = switch (kind) {
                case "PathVariable" -> "PATH";
                case "RequestParam" -> "QUERY";
                case "RequestHeader" -> "HEADER";
                case "CookieValue" -> "COOKIE";
                default -> null;
            };
            if (location == null || (isMapType(parameter.getType().asString()) && !name.declared())) {
                return ParameterParse.unsupported();
            }
            boolean parameterRequired = "PATH".equals(location)
                    ? required.value() && !optional
                    : required.value() && !optional && !hasDefault;
            parameters.add(new HttpApiOperation.Parameter(name.value(), location, parameterRequired,
                    schema(parameter.getType().asString(), classIndex, new HashSet<>(), 0), List.of()));
        }
        return ParameterParse.supported(parameters, body);
    }

    private NameParse bindingName(AnnotationExpr annotation, Parameter parameter) {
        LiteralList explicit = aliasStrings(annotation, "name", "value");
        if (!explicit.supported()) {
            return NameParse.unsupported();
        }
        if (explicit.present()) {
            if (explicit.values().size() != 1 || explicit.values().get(0).isBlank()) {
                return NameParse.unsupported();
            }
            return NameParse.supported(explicit.values().get(0).trim(), true);
        }
        String fallback = parameter.getNameAsString();
        return fallback == null || fallback.isBlank() ? NameParse.unsupported() : NameParse.supported(fallback, false);
    }

    private boolean hasNonSentinelDefault(AnnotationExpr annotation) {
        Optional<Expression> raw = member(annotation, "defaultValue");
        if (raw.isEmpty()) {
            return false;
        }
        Expression value = raw.get();
        if (value.isFieldAccessExpr() && "DEFAULT_NONE".equals(value.asFieldAccessExpr().getNameAsString())) {
            return false;
        }
        return !(value.isNameExpr() && "DEFAULT_NONE".equals(value.asNameExpr().getNameAsString()));
    }

    private StatusParse responseStatus(ClassOrInterfaceDeclaration declaration, MethodDeclaration method) {
        Optional<AnnotationExpr> source = annotation(method.getAnnotations(), "ResponseStatus")
                .or(() -> annotation(declaration.getAnnotations(), "ResponseStatus"));
        if (source.isEmpty()) {
            return StatusParse.supported("DEFAULT");
        }
        Optional<Expression> raw = member(source.get(), "code").or(() -> member(source.get(), "value"));
        if (raw.isEmpty()) {
            return StatusParse.unsupported();
        }
        String status = staticStatus(raw.get());
        return status == null ? StatusParse.unsupported() : StatusParse.supported(status);
    }

    private String staticStatus(Expression expression) {
        if (expression.isIntegerLiteralExpr()) {
            String value = expression.asIntegerLiteralExpr().getValue();
            return value.matches("[1-5][0-9]{2}") ? value : null;
        }
        String enumName;
        if (expression.isFieldAccessExpr()) {
            enumName = expression.asFieldAccessExpr().getNameAsString();
        } else if (expression.isNameExpr()) {
            enumName = expression.asNameExpr().getNameAsString();
        } else {
            return null;
        }
        return switch (enumName.toUpperCase(Locale.ROOT)) {
            case "CONTINUE" -> "100";
            case "OK" -> "200";
            case "CREATED" -> "201";
            case "ACCEPTED" -> "202";
            case "NO_CONTENT" -> "204";
            case "BAD_REQUEST" -> "400";
            case "UNAUTHORIZED" -> "401";
            case "FORBIDDEN" -> "403";
            case "NOT_FOUND" -> "404";
            case "CONFLICT" -> "409";
            case "UNPROCESSABLE_ENTITY" -> "422";
            case "INTERNAL_SERVER_ERROR" -> "500";
            case "NOT_IMPLEMENTED" -> "501";
            case "BAD_GATEWAY" -> "502";
            case "SERVICE_UNAVAILABLE" -> "503";
            default -> null;
        };
    }

    private JsonNode schema(String rawType,
                            Map<String, TypeDeclaration<?>> classIndex,
                            Set<String> visiting,
                            int depth) {
        String type = unwrapType(rawType);
        if (type == null || type.isBlank()) {
            return JsonNodeFactory.instance.objectNode().put("type", "object");
        }
        if ("void".equalsIgnoreCase(type) || "Void".equals(type)) {
            // The Starter descriptor represents an intentionally bodyless response as an empty
            // schema object; retain that shared wire shape so it is not a false source conflict.
            return JsonNodeFactory.instance.objectNode();
        }
        if (type.endsWith("[]")) {
            ObjectNode array = JsonNodeFactory.instance.objectNode();
            array.put("type", "array");
            array.set("items", nonNullSchema(schema(type.substring(0, type.length() - 2), classIndex, visiting, depth + 1)));
            return array;
        }
        String outer = simpleType(outerType(type)).toLowerCase(Locale.ROOT);
        List<String> arguments = typeArguments(type);
        if (COLLECTION_TYPES.contains(outer)) {
            ObjectNode array = JsonNodeFactory.instance.objectNode();
            array.put("type", "array");
            String item = arguments.isEmpty() ? null : arguments.get(0);
            array.set("items", nonNullSchema(schema(item, classIndex, visiting, depth + 1)));
            return array;
        }
        if (MAP_TYPES.contains(outer)) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            object.put("type", "object");
            String value = arguments.size() < 2 ? null : arguments.get(1);
            object.set("additionalProperties", nonNullSchema(schema(value, classIndex, visiting, depth + 1)));
            return object;
        }
        String simple = simpleType(type);
        String lower = simple.toLowerCase(Locale.ROOT);
        if (STRING_TYPES.contains(lower)) {
            return JsonNodeFactory.instance.objectNode().put("type", "string");
        }
        if (INTEGER_TYPES.contains(lower)) {
            return JsonNodeFactory.instance.objectNode().put("type", "integer");
        }
        if (NUMBER_TYPES.contains(lower)) {
            return JsonNodeFactory.instance.objectNode().put("type", "number");
        }
        if ("boolean".equals(lower)) {
            return JsonNodeFactory.instance.objectNode().put("type", "boolean");
        }
        TypeDeclaration<?> declaration = lookup(type, classIndex);
        if (declaration instanceof EnumDeclaration) {
            return JsonNodeFactory.instance.objectNode().put("type", "string");
        }
        if (declaration == null || depth >= 6) {
            return JsonNodeFactory.instance.objectNode().put("type", "object");
        }
        String key = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        if (!visiting.add(key)) {
            return JsonNodeFactory.instance.objectNode().put("type", "object");
        }
        try {
            TreeMap<String, JsonNode> properties = new TreeMap<>();
            Set<String> required = new java.util.TreeSet<>();
            if (declaration instanceof RecordDeclaration record) {
                for (Parameter component : record.getParameters()) {
                    String property = jsonName(component.getAnnotations(), component.getNameAsString());
                    properties.put(property, nonNullSchema(schema(component.getType().asString(), classIndex, visiting, depth + 1)));
                    if (required(component.getAnnotations())) {
                        required.add(property);
                    }
                }
            } else if (declaration instanceof ClassOrInterfaceDeclaration clazz) {
                for (FieldDeclaration field : clazz.getFields()) {
                    if (field.isStatic() || annotation(field.getAnnotations(), "JsonIgnore").isPresent()) {
                        continue;
                    }
                    for (VariableDeclarator variable : field.getVariables()) {
                        String property = jsonName(field.getAnnotations(), variable.getNameAsString());
                        properties.put(property, nonNullSchema(schema(variable.getType().asString(), classIndex, visiting, depth + 1)));
                        if (required(field.getAnnotations())) {
                            required.add(property);
                        }
                    }
                }
            }
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            object.put("type", "object");
            if (!properties.isEmpty()) {
                ObjectNode fields = object.putObject("properties");
                properties.forEach(fields::set);
            }
            if (!required.isEmpty()) {
                ArrayNode requiredNode = object.putArray("required");
                required.forEach(requiredNode::add);
            }
            return object;
        } finally {
            visiting.remove(key);
        }
    }

    private JsonNode nonNullSchema(JsonNode value) {
        return value == null ? JsonNodeFactory.instance.objectNode().put("type", "object") : value;
    }

    private String jsonName(NodeList<AnnotationExpr> annotations, String fallback) {
        LiteralList value = annotation(annotations, "JsonProperty")
                .map(annotation -> aliasStrings(annotation, "value", "value"))
                .orElse(LiteralList.absent());
        if (value.supported() && value.present() && value.values().size() == 1 && !value.values().get(0).isBlank()) {
            return value.values().get(0).trim();
        }
        return fallback;
    }

    private boolean required(NodeList<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            String name = simpleName(annotation.getNameAsString());
            if (REQUIRED_FIELD_ANNOTATIONS.contains(name)) {
                return true;
            }
            if ("Schema".equals(name) || "JsonProperty".equals(name)) {
                BooleanParse flag = booleanAttribute(annotation, "required", false);
                if (flag.supported() && flag.value()) {
                    return true;
                }
            }
        }
        return false;
    }

    private String unwrapType(String raw) {
        if (raw == null) {
            return null;
        }
        String result = raw.trim();
        while (true) {
            String outer = simpleType(outerType(result)).toLowerCase(Locale.ROOT);
            List<String> arguments = typeArguments(result);
            if (!RESPONSE_WRAPPERS.contains(outer) || arguments.isEmpty()) {
                return result;
            }
            result = arguments.get(0).trim();
        }
    }

    private String responseBodyType(String raw) {
        return unwrapType(raw);
    }

    private boolean optionalType(String raw) {
        return "optional".equals(simpleType(outerType(raw)).toLowerCase(Locale.ROOT));
    }

    private boolean isMapType(String raw) {
        return MAP_TYPES.contains(simpleType(outerType(raw)).toLowerCase(Locale.ROOT));
    }

    private TypeDeclaration<?> lookup(String raw, Map<String, TypeDeclaration<?>> classIndex) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        TypeDeclaration<?> direct = classIndex.get(raw.trim());
        return direct != null ? direct : classIndex.get(simpleType(raw));
    }

    private boolean addConditions(List<HttpApiOperation.MappingCondition> target, String kind, List<String> rawValues) {
        for (String raw : rawValues) {
            HttpApiOperation.MappingCondition parsed = condition(kind, raw);
            if (parsed == null) {
                return false;
            }
            target.add(parsed);
        }
        return true;
    }

    /** Spring moves Content-Type and Accept header predicates into consumes/produces conditions. */
    private HeaderParse headerFacts(List<String> rawValues) {
        List<HttpApiOperation.MappingCondition> conditions = new ArrayList<>();
        List<String> consumes = new ArrayList<>();
        List<String> produces = new ArrayList<>();
        for (String raw : rawValues) {
            HttpApiOperation.MappingCondition parsed = condition("HEADER", raw);
            if (parsed == null) {
                return HeaderParse.unsupported();
            }
            String media = mediaExpression(parsed);
            if (media != null && "content-type".equalsIgnoreCase(parsed.name())) {
                consumes.add(media);
            } else if (media != null && "accept".equalsIgnoreCase(parsed.name())) {
                produces.add(media);
            } else {
                conditions.add(parsed);
            }
        }
        if (!validMedia(consumes) || !validMedia(produces)) {
            return HeaderParse.unsupported();
        }
        return HeaderParse.supported(sortedConditions(conditions), normalizedMedia(consumes), normalizedMedia(produces));
    }

    private String mediaExpression(HttpApiOperation.MappingCondition condition) {
        if ("EQUALS".equals(condition.operator())) {
            return condition.value();
        }
        if ("NOT_EQUALS".equals(condition.operator())) {
            return "!" + condition.value();
        }
        return null;
    }

    private HttpApiOperation.MappingCondition condition(String kind, String raw) {
        if (raw == null) {
            return null;
        }
        String expression = raw.trim();
        if (expression.isEmpty() || expression.contains("\r") || expression.contains("\n")) {
            return null;
        }
        String name;
        String operator;
        String value = null;
        if (expression.startsWith("!")) {
            name = expression.substring(1).trim();
            operator = "ABSENT";
        } else {
            int notEquals = expression.indexOf("!=");
            int equals = notEquals >= 0 ? -1 : expression.indexOf('=');
            if (notEquals >= 0) {
                name = expression.substring(0, notEquals).trim();
                value = expression.substring(notEquals + 2).trim();
                operator = "NOT_EQUALS";
            } else if (equals >= 0) {
                name = expression.substring(0, equals).trim();
                value = expression.substring(equals + 1).trim();
                operator = "EQUALS";
            } else {
                name = expression;
                operator = "PRESENT";
            }
        }
        if (name.isEmpty() || (value != null && value.isEmpty()) || (value != null &&
                (sensitiveName(name) || SECRET_ASSIGNMENT.matcher(value).find()))) {
            return null;
        }
        return new HttpApiOperation.MappingCondition(kind, name, operator, value);
    }

    private List<HttpApiOperation.MappingCondition> sortedConditions(List<HttpApiOperation.MappingCondition> values) {
        return values.stream().distinct().sorted(Comparator
                .comparing(HttpApiOperation.MappingCondition::kind)
                .thenComparing(HttpApiOperation.MappingCondition::name, Comparator.nullsFirst(String::compareTo))
                .thenComparing(HttpApiOperation.MappingCondition::operator)
                .thenComparing(HttpApiOperation.MappingCondition::value, Comparator.nullsFirst(String::compareTo)))
                .toList();
    }

    private boolean sensitiveName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("authorization") || normalized.equals("cookie") || normalized.equals("set-cookie")
                || normalized.contains("api-key") || normalized.contains("apikey") || normalized.contains("token")
                || normalized.contains("password") || normalized.contains("secret") || normalized.contains("credential");
    }

    private LiteralList aliasStrings(AnnotationExpr annotation, String primary, String secondary) {
        LiteralList first = strings(annotation, primary);
        LiteralList second = primary.equals(secondary) ? LiteralList.absent() : strings(annotation, secondary);
        if (!first.supported() || !second.supported()) {
            return LiteralList.unsupported();
        }
        if (first.present() && second.present() && !new LinkedHashSet<>(first.values()).equals(new LinkedHashSet<>(second.values()))) {
            return LiteralList.unsupported();
        }
        return first.present() ? first : second;
    }

    private LiteralList strings(AnnotationExpr annotation, String name) {
        Optional<Expression> member = member(annotation, name);
        if (member.isEmpty()) {
            return LiteralList.absent();
        }
        List<Expression> expressions = expressions(member.get());
        if (expressions == null) {
            return LiteralList.unsupported();
        }
        List<String> values = new ArrayList<>();
        for (Expression expression : expressions) {
            if (!expression.isStringLiteralExpr()) {
                return LiteralList.unsupported();
            }
            values.add(expression.asStringLiteralExpr().asString());
        }
        return LiteralList.supported(values);
    }

    private List<Expression> expressions(Expression expression) {
        if (expression instanceof ArrayInitializerExpr array) {
            return new ArrayList<>(array.getValues());
        }
        return List.of(expression);
    }

    private Optional<Expression> member(AnnotationExpr annotation, String name) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return "value".equals(name) ? Optional.of(single.getMemberValue()) : Optional.empty();
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream().filter(pair -> name.equals(pair.getNameAsString()))
                    .map(MemberValuePair::getValue).findFirst();
        }
        return Optional.empty();
    }

    private Optional<AnnotationExpr> annotation(NodeList<AnnotationExpr> annotations, String simpleName) {
        return annotations.stream().filter(annotation -> simpleName.equals(simpleName(annotation.getNameAsString()))).findFirst();
    }

    private BooleanParse booleanAttribute(AnnotationExpr annotation, String name, boolean fallback) {
        Optional<Expression> raw = member(annotation, name);
        if (raw.isEmpty()) {
            return BooleanParse.supported(fallback);
        }
        if (raw.get() instanceof BooleanLiteralExpr literal) {
            return BooleanParse.supported(literal.getValue());
        }
        return BooleanParse.unsupported();
    }

    private List<String> normalizedPaths(List<String> values) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String value : values) {
            String path = value == null ? "" : value.trim();
            if (path.isEmpty()) {
                paths.add("");
            } else {
                paths.add(path);
            }
        }
        return paths.isEmpty() ? List.of("") : paths.stream().sorted().toList();
    }

    private boolean validPaths(List<String> values) {
        for (String value : values) {
            if (value == null) {
                return false;
            }
            String path = value.trim();
            if (path.contains("?") || path.contains("#") || path.contains("\r") || path.contains("\n")) {
                return false;
            }
        }
        return true;
    }

    private boolean validMedia(List<String> values) {
        for (String value : values) {
            if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                return false;
            }
        }
        return true;
    }

    private List<String> normalizedMedia(List<String> values) {
        return values.stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
    }

    private List<String> mergeMedia(List<String> left, List<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(left);
        values.addAll(right);
        return normalizedMedia(new ArrayList<>(values));
    }

    private List<String> union(List<String> left, List<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(left);
        values.addAll(right);
        return values.stream().sorted().toList();
    }

    private String joinPath(String left, String right) {
        String a = normalizePath(left);
        String b = normalizePath(right);
        if ("/".equals(a)) {
            return b;
        }
        if ("/".equals(b)) {
            return a;
        }
        return (a + b).replaceAll("/+", "/");
    }

    private String contextPath(String value) {
        return value == null || value.isBlank() || "/".equals(value.trim()) ? "" : normalizePath(value);
    }

    private String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }
        String normalized = value.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isController(ClassOrInterfaceDeclaration declaration, ScanOptions options) {
        boolean onlyRest = options == null || options.getOnlyRestController() == null
                || Boolean.TRUE.equals(options.getOnlyRestController());
        return declaration.getAnnotations().stream().map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(name -> onlyRest ? "RestController".equals(name)
                        : ("RestController".equals(name) || "Controller".equals(name)));
    }

    private boolean skipByClassFqn(ClassOrInterfaceDeclaration declaration, ScanOptions options) {
        if (options == null) {
            return false;
        }
        String fqn = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        if (options.getClassIncludeRegex() != null && !options.getClassIncludeRegex().isBlank()
                && !Pattern.compile(options.getClassIncludeRegex(), Pattern.CASE_INSENSITIVE).matcher(fqn).find()) {
            return true;
        }
        return options.getClassExcludeRegex() != null && !options.getClassExcludeRegex().isBlank()
                && Pattern.compile(options.getClassExcludeRegex(), Pattern.CASE_INSENSITIVE).matcher(fqn).find();
    }

    private boolean skipDeprecated(ScanOptions options) {
        return options != null && Boolean.TRUE.equals(options.getSkipDeprecated());
    }

    private boolean isDeprecated(NodeList<AnnotationExpr> annotations) {
        return annotation(annotations, "Deprecated").isPresent();
    }

    private boolean hasFilteringScope(ScanOptions options) {
        return options != null && ((options.getHttpMethodWhitelist() != null && !options.getHttpMethodWhitelist().isEmpty())
                || (options.getClassIncludeRegex() != null && !options.getClassIncludeRegex().isBlank())
                || (options.getClassExcludeRegex() != null && !options.getClassExcludeRegex().isBlank())
                || Boolean.TRUE.equals(options.getSkipDeprecated()));
    }

    private boolean httpMethodAllowed(String method, ScanOptions options) {
        if (options == null || options.getHttpMethodWhitelist() == null || options.getHttpMethodWhitelist().isEmpty()) {
            return true;
        }
        return options.getHttpMethodWhitelist().stream().filter(value -> value != null)
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).anyMatch(method::equals);
    }

    private boolean readOnly(String method) {
        return Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(method);
    }

    private String sourceLocation(Path root, Path javaFile, ClassOrInterfaceDeclaration declaration, MethodDeclaration method) {
        String fqn = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        return displayPath(root, javaFile) + "#" + fqn + "#" + methodSignature(method);
    }

    private String sourceKey(Path root,
                             Path javaFile,
                             ClassOrInterfaceDeclaration declaration,
                             MethodDeclaration method,
                             OperationVariant variant) {
        String fqn = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        String conditionKey = variant.conditions().stream()
                .map(item -> item.kind() + ":" + item.name() + ":" + item.operator() + ":" + nullSafe(item.value()))
                .sorted().reduce("", (left, right) -> left + "|" + right);
        String payload = displayPath(root, javaFile) + "|" + fqn + "|" + methodSignature(method) + "|"
                + variant.method() + "|" + variant.endpointPath() + "|" + String.join(",", variant.consumes()) + "|"
                + String.join(",", variant.produces()) + conditionKey;
        return "controller:" + sha256(payload);
    }

    private HttpApiOperation withRevision(HttpApiOperation operation) {
        String conditions = operation.mappingConditions().stream()
                .map(item -> item.kind() + ":" + item.name() + ":" + item.operator() + ":" + nullSafe(item.value()))
                .sorted().reduce("", (left, right) -> left + "|" + right);
        String parameters = operation.parameters().stream()
                .map(item -> item.location() + ":" + item.name() + ":" + item.required() + ":" + item.schema())
                .sorted().reduce("", (left, right) -> left + "|" + right);
        String responses = operation.responses().stream()
                .map(item -> item.status() + ":" + item.schema()).sorted().reduce("", (left, right) -> left + "|" + right);
        String revision = "scan:" + sha256(operation.sourceKey() + "|" + operation.httpMethod() + "|"
                + operation.contextPath() + "|" + operation.endpointPath() + "|" + conditions + "|" + parameters + "|"
                + nullSafe(operation.requestBody() == null ? null : operation.requestBody().schema()) + "|" + responses);
        return new HttpApiOperation(operation.sourceKey(), operation.sourceLocation(), revision,
                operation.httpMethod(), operation.contextPath(), operation.endpointPath(), operation.consumes(),
                operation.produces(), operation.mappingConditions(), operation.parameters(), operation.requestBody(),
                operation.responses(), operation.authenticationState(), operation.authenticationSchemes(),
                operation.requiredHeaderNames(), operation.sideEffect());
    }

    private String methodSignature(MethodDeclaration method) {
        return method.getNameAsString() + "(" + method.getParameters().stream()
                .map(parameter -> parameter.getType().asString()).reduce((left, right) -> left + "," + right).orElse("") + ")";
    }

    private String displayPath(Path root, Path javaFile) {
        try {
            Path base = Files.isDirectory(root) ? root : root.getParent();
            if (base != null) {
                return base.toAbsolutePath().normalize().relativize(javaFile.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // A relative filename remains non-secret and avoids exposing an absolute machine path.
        }
        Path filename = javaFile.getFileName();
        return filename == null ? "controller.java" : filename.toString();
    }

    private String outerType(String type) {
        if (type == null) {
            return "";
        }
        int angle = type.indexOf('<');
        return (angle < 0 ? type : type.substring(0, angle)).trim();
    }

    private List<String> typeArguments(String type) {
        if (type == null) {
            return List.of();
        }
        int start = type.indexOf('<');
        int end = type.lastIndexOf('>');
        if (start < 0 || end <= start) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        int depth = 0;
        int tokenStart = start + 1;
        for (int index = start + 1; index < end; index++) {
            char value = type.charAt(index);
            if (value == '<') {
                depth++;
            } else if (value == '>') {
                depth--;
            } else if (value == ',' && depth == 0) {
                result.add(type.substring(tokenStart, index).trim());
                tokenStart = index + 1;
            }
        }
        String last = type.substring(tokenStart, end).trim();
        if (!last.isEmpty()) {
            result.add(last);
        }
        return result;
    }

    private String simpleType(String type) {
        if (type == null) {
            return "";
        }
        String result = outerType(type);
        int dot = result.lastIndexOf('.');
        return (dot < 0 ? result : result.substring(dot + 1)).trim();
    }

    private String simpleName(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return name == null ? "" : (dot < 0 ? name : name.substring(dot + 1));
    }

    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    record Result(List<HttpApiOperation> operations, boolean complete) {
    }

    private record FileResult(List<HttpApiOperation> operations, boolean complete) {
    }

    private record Mapping(List<String> paths,
                           List<String> methods,
                           List<String> consumes,
                           boolean consumesDeclared,
                           List<String> produces,
                           boolean producesDeclared,
                           List<HttpApiOperation.MappingCondition> conditions) {
        static Mapping empty() {
            return new Mapping(List.of(""), List.of(), List.of(), false, List.of(), false, List.of());
        }
    }

    private record MappingParse(boolean present, boolean supported, Mapping mapping) {
        static MappingParse absent() {
            return new MappingParse(false, true, null);
        }

        static MappingParse unsupported() {
            return new MappingParse(true, false, null);
        }

        static MappingParse supported(Mapping mapping) {
            return new MappingParse(true, true, mapping);
        }
    }

    private record MethodList(boolean supported, List<String> values) {
        static MethodList unsupported() {
            return new MethodList(false, List.of());
        }

        static MethodList supported(List<String> values) {
            return new MethodList(true, List.copyOf(values));
        }
    }

    private record HeaderParse(boolean supported,
                               List<HttpApiOperation.MappingCondition> conditions,
                               List<String> consumes,
                               List<String> produces) {
        static HeaderParse unsupported() {
            return new HeaderParse(false, List.of(), List.of(), List.of());
        }

        static HeaderParse supported(List<HttpApiOperation.MappingCondition> conditions,
                                     List<String> consumes,
                                     List<String> produces) {
            return new HeaderParse(true, List.copyOf(conditions), List.copyOf(consumes), List.copyOf(produces));
        }
    }

    private record LiteralList(boolean present, boolean supported, List<String> values) {
        static LiteralList absent() {
            return new LiteralList(false, true, List.of());
        }

        static LiteralList unsupported() {
            return new LiteralList(true, false, List.of());
        }

        static LiteralList supported(List<String> values) {
            return new LiteralList(true, true, List.copyOf(values));
        }
    }

    private record OperationVariant(String method,
                                    String endpointPath,
                                    List<String> consumes,
                                    List<String> produces,
                                    List<HttpApiOperation.MappingCondition> conditions) {
    }

    private record ParameterParse(boolean supported,
                                  List<HttpApiOperation.Parameter> parameters,
                                  HttpApiOperation.RequestBody requestBody) {
        static ParameterParse unsupported() {
            return new ParameterParse(false, List.of(), null);
        }

        static ParameterParse supported(List<HttpApiOperation.Parameter> parameters,
                                        HttpApiOperation.RequestBody requestBody) {
            return new ParameterParse(true, List.copyOf(parameters), requestBody);
        }
    }

    private record NameParse(boolean supported, String value, boolean declared) {
        static NameParse unsupported() {
            return new NameParse(false, null, false);
        }

        static NameParse supported(String value, boolean declared) {
            return new NameParse(true, value, declared);
        }
    }

    private record BooleanParse(boolean supported, boolean value) {
        static BooleanParse unsupported() {
            return new BooleanParse(false, false);
        }

        static BooleanParse supported(boolean value) {
            return new BooleanParse(true, value);
        }
    }

    private record StatusParse(boolean supported, String status) {
        static StatusParse unsupported() {
            return new StatusParse(false, null);
        }

        static StatusParse supported(String status) {
            return new StatusParse(true, status);
        }
    }
}
