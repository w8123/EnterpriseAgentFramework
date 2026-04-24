package com.enterprise.ai.agent.semantic.context;

import com.enterprise.ai.agent.scan.ScanModuleEntity;
import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectToolAdapter;
import com.enterprise.ai.agent.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 根据扫描项目磁盘路径与目标层级，收集 LLM 生成语义文档所需的上下文。
 * <ul>
 *     <li>项目级：README + pom 元信息 + 模块名索引。</li>
 *     <li>模块级：Controller 源码 + 方法体中依赖的 Service / Mapper 类片段。</li>
 *     <li>接口级：单方法源码 + 方法体内调用到的 Service 方法 + 入参出参 DTO 字段。</li>
 * </ul>
 * 所有层都遵守字符预算裁剪，超预算时优先保留签名摘要。
 */
@Component
public class SemanticContextCollector {

    private static final Logger log = LoggerFactory.getLogger(SemanticContextCollector.class);

    /** 1 token ≈ 4 char 的粗略近似，内部按字符预算做裁剪。 */
    public static final int CHAR_BUDGET_PROJECT = 40_000 * 4;
    public static final int CHAR_BUDGET_MODULE = 20_000 * 4;
    public static final int CHAR_BUDGET_TOOL = 8_000 * 4;

    private final JavaParser javaParser = new JavaParser();
    private final ScanModuleService scanModuleService;

    public SemanticContextCollector(ScanModuleService scanModuleService) {
        this.scanModuleService = scanModuleService;
    }

    // ==================== 公共入口 ====================

    public SemanticContext collectForProject(ScanProjectEntity project,
                                             List<ScanModuleEntity> modules) {
        Path root = resolveRoot(project);
        String readme = root == null ? "" : readRelative(root, "README.md", CHAR_BUDGET_PROJECT / 2);
        if (readme.isBlank() && root != null) {
            readme = readRelative(root, "README.MD", CHAR_BUDGET_PROJECT / 2);
        }
        String pomDesc = root == null ? "" : extractPomDescription(root);
        List<String> moduleIndex = modules == null ? List.of() : modules.stream()
                .map(m -> firstNonBlank(m.getDisplayName(), m.getName()))
                .toList();
        return new SemanticContext(
                SemanticContext.LEVEL_PROJECT,
                project.getName(),
                pomDesc,
                trim(readme, CHAR_BUDGET_PROJECT / 2),
                moduleIndex,
                null, null, null, null, null, null, null, null);
    }

    public SemanticContext collectForModule(ScanProjectEntity project,
                                            ScanModuleEntity module,
                                            List<ToolDefinitionEntity> moduleTools) {
        Path root = resolveRoot(project);
        JavaSourceIndex index = JavaSourceIndex.build(root);

        List<String> classNames = scanModuleService.parseClasses(module.getSourceClasses());
        if (classNames.isEmpty()) {
            classNames = List.of(module.getName());
        }

        int remaining = CHAR_BUDGET_MODULE;
        List<SemanticContext.SourceSnippet> controllerSources = new ArrayList<>();
        Set<String> serviceClassNames = new LinkedHashSet<>();
        for (String className : classNames) {
            Optional<Path> file = index.find(className);
            if (file.isEmpty()) {
                continue;
            }
            String source = readPath(file.get(), remaining);
            boolean trimmed = source.endsWith("\n/* ...truncated... */");
            controllerSources.add(new SemanticContext.SourceSnippet(className, source, trimmed));
            remaining -= source.length();
            serviceClassNames.addAll(collectDependencyClassNames(source));
            if (remaining <= 0) {
                break;
            }
        }

        List<SemanticContext.SourceSnippet> serviceSnippets = new ArrayList<>();
        List<SemanticContext.SourceSnippet> mapperSnippets = new ArrayList<>();
        for (String ref : serviceClassNames) {
            if (remaining <= 0) {
                break;
            }
            Optional<Path> refPath = index.find(ref);
            if (refPath.isEmpty()) {
                continue;
            }
            int budget = Math.min(remaining, CHAR_BUDGET_MODULE / 4);
            String snippet = readPath(refPath.get(), budget);
            SemanticContext.SourceSnippet s = new SemanticContext.SourceSnippet(ref, snippet, snippet.length() >= budget);
            if (ref.endsWith("Mapper") || ref.endsWith("Repository") || ref.endsWith("Dao")) {
                mapperSnippets.add(s);
            } else {
                serviceSnippets.add(s);
            }
            remaining -= snippet.length();
        }

        return new SemanticContext(
                SemanticContext.LEVEL_MODULE,
                project.getName(),
                null,
                null,
                null,
                firstNonBlank(module.getDisplayName(), module.getName()),
                controllerSources,
                serviceSnippets,
                mapperSnippets,
                List.of(),
                null, null, null);
    }

    public SemanticContext collectForTool(ScanProjectEntity project,
                                          ToolDefinitionEntity tool,
                                          ScanModuleEntity module) {
        return collectForTool(project, tool, module, SemanticContext.LEVEL_TOOL);
    }

    public SemanticContext collectForScanProjectTool(ScanProjectEntity project,
                                                     ScanProjectToolEntity tool,
                                                     ScanModuleEntity module) {
        return collectForTool(project, ScanProjectToolAdapter.toDefinitionEntity(tool), module, SemanticContext.LEVEL_SCAN_TOOL);
    }

    private SemanticContext collectForTool(ScanProjectEntity project,
                                         ToolDefinitionEntity tool,
                                         ScanModuleEntity module,
                                         String contextLevel) {
        Path root = resolveRoot(project);
        JavaSourceIndex index = JavaSourceIndex.build(root);

        String controllerClassName = extractControllerClassName(tool);
        String methodSignature = extractMethodName(tool);

        int remaining = CHAR_BUDGET_TOOL;
        String methodSource = "";
        Set<String> referencedTypes = new LinkedHashSet<>();
        if (StringUtils.hasText(controllerClassName) && StringUtils.hasText(methodSignature)) {
            Optional<Path> controllerPath = index.find(controllerClassName);
            if (controllerPath.isPresent()) {
                MethodExtraction extraction = extractMethodSource(controllerPath.get(), methodSignature, remaining);
                methodSource = extraction.source();
                referencedTypes.addAll(extraction.referencedTypes());
                remaining -= methodSource.length();
            }
        }

        List<SemanticContext.SourceSnippet> serviceSnippets = new ArrayList<>();
        List<SemanticContext.SourceSnippet> dtoSnippets = new ArrayList<>();

        String bodyType = tool.getRequestBodyType();
        String respType = stripGenerics(tool.getResponseType());
        if (StringUtils.hasText(bodyType)) {
            referencedTypes.add(stripGenerics(bodyType));
        }
        if (StringUtils.hasText(respType)) {
            referencedTypes.add(respType);
        }

        for (String type : referencedTypes) {
            if (remaining <= 0) {
                break;
            }
            Optional<Path> p = index.find(type);
            if (p.isEmpty()) {
                continue;
            }
            int budget = Math.min(remaining, CHAR_BUDGET_TOOL / 3);
            String snippet = readPath(p.get(), budget);
            SemanticContext.SourceSnippet item = new SemanticContext.SourceSnippet(type, snippet, snippet.length() >= budget);
            if (type.endsWith("Service") || type.endsWith("ServiceImpl") || type.endsWith("Manager")) {
                serviceSnippets.add(item);
            } else {
                dtoSnippets.add(item);
            }
            remaining -= snippet.length();
        }

        String endpoint = String.format("%s %s%s",
                tool.getHttpMethod() == null ? "" : tool.getHttpMethod(),
                tool.getContextPath() == null ? "" : tool.getContextPath(),
                tool.getEndpointPath() == null ? "" : tool.getEndpointPath()).trim();

        return new SemanticContext(
                contextLevel,
                project.getName(),
                null,
                null,
                null,
                module == null ? null : firstNonBlank(module.getDisplayName(), module.getName()),
                List.of(),
                serviceSnippets,
                List.of(),
                dtoSnippets,
                tool.getName(),
                endpoint,
                methodSource);
    }

    // ==================== 内部辅助 ====================

    private Path resolveRoot(ScanProjectEntity project) {
        if (project == null || !StringUtils.hasText(project.getScanPath())) {
            return null;
        }
        Path path = Path.of(project.getScanPath());
        return Files.exists(path) ? path : null;
    }

    private String readRelative(Path root, String rel, int budget) {
        Path p = root.resolve(rel);
        if (!Files.exists(p) || !Files.isRegularFile(p)) {
            return "";
        }
        return readPath(p, budget);
    }

    private String readPath(Path path, int budget) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return trim(content, budget);
        } catch (IOException ex) {
            log.warn("[SemanticContextCollector] read failed: {}", path, ex);
            return "";
        }
    }

    /**
     * 从 pom.xml 抽取 <description> 文本。不做完整 XML 解析，保持零依赖。
     */
    private String extractPomDescription(Path root) {
        Path pom = root.resolve("pom.xml");
        if (!Files.exists(pom) || !Files.isRegularFile(pom)) {
            return "";
        }
        String content = readPath(pom, 16_000);
        int start = content.indexOf("<description>");
        if (start < 0) {
            return "";
        }
        int end = content.indexOf("</description>", start);
        if (end < 0) {
            return "";
        }
        return content.substring(start + "<description>".length(), end).trim();
    }

    private Set<String> collectDependencyClassNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        ParseResult<CompilationUnit> result;
        try {
            result = javaParser.parse(source);
        } catch (ParseProblemException ex) {
            return names;
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return names;
        }
        CompilationUnit cu = result.getResult().get();
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            for (VariableDeclarator var : field.getVariables()) {
                String typeName = stripGenerics(var.getTypeAsString());
                if (looksLikeProjectType(typeName)) {
                    names.add(typeName);
                }
            }
        });
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> clazz.getConstructors().forEach(ctor ->
                ctor.getParameters().forEach(p -> {
                    String typeName = stripGenerics(p.getType().asString());
                    if (looksLikeProjectType(typeName)) {
                        names.add(typeName);
                    }
                })));
        return names;
    }

    private boolean looksLikeProjectType(String typeName) {
        if (!StringUtils.hasText(typeName)) {
            return false;
        }
        if (!Character.isUpperCase(typeName.charAt(0))) {
            return false;
        }
        return typeName.endsWith("Service") || typeName.endsWith("ServiceImpl")
                || typeName.endsWith("Manager") || typeName.endsWith("Mapper")
                || typeName.endsWith("Repository") || typeName.endsWith("Dao")
                || typeName.endsWith("Client");
    }

    private String extractControllerClassName(ToolDefinitionEntity tool) {
        String loc = tool.getSourceLocation();
        if (!StringUtils.hasText(loc)) {
            return null;
        }
        String[] parts = loc.split("#");
        if (parts.length >= 2) {
            return parts[1].trim();
        }
        return null;
    }

    private String extractMethodName(ToolDefinitionEntity tool) {
        String loc = tool.getSourceLocation();
        if (!StringUtils.hasText(loc)) {
            return null;
        }
        String[] parts = loc.split("#");
        if (parts.length >= 3) {
            return parts[2].trim();
        }
        return null;
    }

    private MethodExtraction extractMethodSource(Path javaFile, String methodName, int budget) {
        ParseResult<CompilationUnit> result;
        try {
            result = javaParser.parse(Files.readString(javaFile, StandardCharsets.UTF_8));
        } catch (IOException | ParseProblemException ex) {
            return new MethodExtraction("", Set.of());
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return new MethodExtraction("", Set.of());
        }
        CompilationUnit cu = result.getResult().get();
        Set<String> refs = new LinkedHashSet<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> clazz.getFields().forEach(field -> {
            for (VariableDeclarator var : field.getVariables()) {
                String type = stripGenerics(var.getTypeAsString());
                if (looksLikeProjectType(type)) {
                    refs.add(type);
                }
            }
        }));
        try (Stream<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class).stream()) {
            Optional<MethodDeclaration> match = methods
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .findFirst();
            if (match.isEmpty()) {
                return new MethodExtraction("", refs);
            }
            MethodDeclaration method = match.get();
            method.getParameters().forEach(p -> {
                String type = stripGenerics(p.getType().asString());
                if (Character.isUpperCase(type.isEmpty() ? 'a' : type.charAt(0))) {
                    refs.add(type);
                }
            });
            String typeRet = stripGenerics(method.getType().asString());
            if (!typeRet.isBlank() && Character.isUpperCase(typeRet.charAt(0))) {
                refs.add(typeRet);
            }
            return new MethodExtraction(trim(method.toString(), budget), refs);
        }
    }

    private String stripGenerics(String type) {
        if (type == null) {
            return "";
        }
        int idx = type.indexOf('<');
        return (idx > 0 ? type.substring(0, idx) : type).trim();
    }

    private String trim(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, Math.max(0, maxChars)) + "\n/* ...truncated... */";
    }

    private String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : (b == null ? "" : b);
    }

    private record MethodExtraction(String source, Set<String> referencedTypes) {
    }
}
