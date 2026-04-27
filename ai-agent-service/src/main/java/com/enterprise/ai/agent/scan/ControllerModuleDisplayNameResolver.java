package com.enterprise.ai.agent.scan;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 从 Controller 源码解析模块展示名。优先级与「接口说明来源」配置一致：类 Javadoc、类上 Swagger {@code Api}、
 * OpenAPI {@code Tag}/{@code Tags}、无则回退为类名（与 METHOD_NAME 兜底一致）。
 * {@link ScanSettings#getDescriptionSourceEnabled()} 中置为 false 的项不参与（与扫描器对端点说明的处理对齐）。
 */
public final class ControllerModuleDisplayNameResolver {

    private ControllerModuleDisplayNameResolver() {
    }

    /**
     * @param scanRoot      扫描项目根路径（目录或单文件）
     * @param classSimpleName Controller 简单类名，与 sourceLocation 第二段一致
     * @param sourceLocation  与扫描器约定一致，如 {@code OrderController.java#OrderController#getOrder}
     * @return 有 Javadoc 或注解文案时返回；无法解析或非 Java Controller 时为空
     * @param settings 项目扫描设置；为 null 时使用全默认（与未配置 JSON 时一致）
     */
    public static Optional<String> resolve(Path scanRoot, String classSimpleName, String sourceLocation, ScanSettings settings) {
        if (scanRoot == null || !StringUtils.hasText(classSimpleName) || !StringUtils.hasText(sourceLocation)) {
            return Optional.empty();
        }
        String[] parts = sourceLocation.split("#");
        if (parts.length < 2 || !StringUtils.hasText(parts[0])) {
            return Optional.empty();
        }
        String fileLeaf = parts[0].trim();
        if (!fileLeaf.endsWith(".java")) {
            return Optional.empty();
        }
        if (!Objects.equals(classSimpleName, parts[1].trim())) {
            return Optional.empty();
        }
        Optional<Path> javaFile = findJavaFile(scanRoot, fileLeaf);
        if (javaFile.isEmpty()) {
            return Optional.empty();
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaFile.get());
            return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> classSimpleName.equals(c.getNameAsString()))
                    .findFirst()
                    .flatMap(c -> resolveFromDeclaration(c, settings));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * 不传入扫描设置时，等价于全默认（四种来源按默认顺序、全部开启）。
     */
    public static Optional<String> resolve(Path scanRoot, String classSimpleName, String sourceLocation) {
        return resolve(scanRoot, classSimpleName, sourceLocation, null);
    }

    private static Optional<Path> findJavaFile(Path scanRoot, String fileLeaf) {
        if (!Files.exists(scanRoot)) {
            return Optional.empty();
        }
        try {
            if (Files.isRegularFile(scanRoot) && scanRoot.getFileName().toString().equals(fileLeaf)) {
                return Optional.of(scanRoot);
            }
            if (!Files.isDirectory(scanRoot)) {
                return Optional.empty();
            }
            try (Stream<Path> stream = Files.walk(scanRoot)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(fileLeaf))
                        .findFirst();
            }
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private static List<String> descriptionSourceKeys(ScanSettings settings) {
        ScanSettings s = settings != null ? settings : ScanSettings.defaults();
        List<String> p = s.getDescriptionSourceOrder();
        if (p == null || p.isEmpty()) {
            p = List.of("JAVADOC", "SWAGGER_API_OPERATION", "OPENAPI_OPERATION", "METHOD_NAME");
        } else {
            p = new ArrayList<>(p);
        }
        return applySourceEnabled(p, s.getDescriptionSourceEnabled());
    }

    private static List<String> applySourceEnabled(List<String> order, Map<String, Boolean> enabled) {
        if (enabled == null || enabled.isEmpty()) {
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

    private static Optional<String> resolveFromDeclaration(ClassOrInterfaceDeclaration decl, ScanSettings settings) {
        for (String key : descriptionSourceKeys(settings)) {
            if (key == null) {
                continue;
            }
            String u = key.trim();
            if ("JAVADOC".equals(u)) {
                Optional<String> javadoc = decl.getJavadoc()
                        .map(j -> j.getDescription().toText().trim())
                        .filter(StringUtils::hasText);
                if (javadoc.isPresent()) {
                    return javadoc;
                }
            } else if ("SWAGGER_API_OPERATION".equals(u)) {
                Optional<String> sw = extractSwaggerApiClassTitle(decl);
                if (sw.isPresent()) {
                    return sw;
                }
            } else if ("OPENAPI_OPERATION".equals(u)) {
                Optional<String> oa = extractOpenApiTagClassTitle(decl);
                if (oa.isPresent()) {
                    return oa;
                }
            } else if ("METHOD_NAME".equals(u)) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Swagger2 类上 {@code @Api}（tags/value），与端点级 {@code ApiOperation} 同属「Swagger」一类开关。
     */
    private static Optional<String> extractSwaggerApiClassTitle(ClassOrInterfaceDeclaration decl) {
        for (AnnotationExpr ann : decl.getAnnotations()) {
            if (!"Api".equals(ann.getNameAsString())) {
                continue;
            }
            Optional<String> fromApi = extractApiAnnotationTitle(ann);
            if (fromApi.isPresent()) {
                return fromApi;
            }
        }
        return Optional.empty();
    }

    /**
     * OpenAPI3 类上 {@code @Tag} / {@code @Tags}。
     */
    private static Optional<String> extractOpenApiTagClassTitle(ClassOrInterfaceDeclaration decl) {
        for (AnnotationExpr ann : decl.getAnnotations()) {
            String n = ann.getNameAsString();
            if ("Tag".equals(n)) {
                Optional<String> tag = extractNamedStringMember(ann, "name");
                if (tag.isPresent()) {
                    return tag;
                }
            }
            if ("Tags".equals(n)) {
                Optional<String> fromTags = extractTagsAnnotationTitle(ann);
                if (fromTags.isPresent()) {
                    return fromTags;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractApiAnnotationTitle(AnnotationExpr annotation) {
        Optional<String> tags = extractNamedMember(annotation, "tags").flatMap(ControllerModuleDisplayNameResolver::firstStringFromExpression);
        if (tags.isPresent()) {
            return tags;
        }
        return extractNamedMember(annotation, "value").map(ControllerModuleDisplayNameResolver::extractStringValue).filter(StringUtils::hasText);
    }

    private static Optional<String> extractTagsAnnotationTitle(AnnotationExpr annotation) {
        return extractNamedMember(annotation, "value")
                .flatMap(expr -> {
                    if (expr.isArrayInitializerExpr()) {
                        ArrayInitializerExpr array = expr.asArrayInitializerExpr();
                        for (Expression el : array.getValues()) {
                            if (el.isNormalAnnotationExpr() || el.isSingleMemberAnnotationExpr()) {
                                Optional<String> name = extractNamedStringMember(el.asAnnotationExpr(), "name");
                                if (name.isPresent()) {
                                    return name;
                                }
                            }
                        }
                    }
                    return Optional.<String>empty();
                });
    }

    private static Optional<String> extractNamedStringMember(AnnotationExpr annotation, String member) {
        return extractNamedMember(annotation, member)
                .map(ControllerModuleDisplayNameResolver::extractStringValue)
                .filter(StringUtils::hasText);
    }

    private static Optional<String> firstStringFromExpression(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            String s = expression.asStringLiteralExpr().asString().trim();
            return StringUtils.hasText(s) ? Optional.of(s) : Optional.empty();
        }
        if (expression.isArrayInitializerExpr()) {
            for (Expression el : expression.asArrayInitializerExpr().getValues()) {
                Optional<String> one = firstStringFromExpression(el);
                if (one.isPresent()) {
                    return one;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Expression> extractNamedMember(AnnotationExpr annotation, String name) {
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

    private static String extractStringValue(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return expression.asStringLiteralExpr().asString();
        }
        if (expression.isNameExpr()) {
            return expression.asNameExpr().getNameAsString();
        }
        if (expression.isFieldAccessExpr()) {
            return expression.asFieldAccessExpr().getNameAsString();
        }
        return expression.toString().replace("\"", "").trim();
    }
}
