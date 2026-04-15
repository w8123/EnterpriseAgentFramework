package com.enterprise.ai.skill.scanner.generator;

import com.enterprise.ai.skill.scanner.manifest.ToolDefinition;
import com.enterprise.ai.skill.scanner.manifest.ToolManifest;
import com.enterprise.ai.skill.scanner.manifest.ToolParameterDefinition;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 Tool Manifest 生成 Skill Service 脚手架。
 */
public class SkillServiceProjectGenerator {

    public Path generate(ToolManifest manifest, Path outputDir, Path templateDir) {
        manifest.validate();

        GeneratedProject project = GeneratedProject.from(manifest);
        Configuration configuration = createConfiguration(templateDir);
        prepareOutputDirectory(outputDir);

        writeTemplate(configuration, "pom.xml.ftl", project.templateModel(), outputDir.resolve("pom.xml"));
        writeTemplate(configuration, "application.yml.ftl", project.templateModel(),
                outputDir.resolve("src/main/resources/application.yml"));
        writeTemplate(configuration, "SkillAutoConfiguration.java.ftl", project.templateModel(),
                outputDir.resolve("src/main/java/" + project.basePackagePath() + "/SkillAutoConfiguration.java"));
        writeTemplate(configuration, "AutoConfiguration.imports.ftl", project.templateModel(),
                outputDir.resolve("src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
        writeTemplate(configuration, "LegacyProjectClient.java.ftl", project.templateModel(),
                outputDir.resolve("src/main/java/" + project.basePackagePath() + "/" + project.clientClassName() + ".java"));

        for (GeneratedTool tool : project.tools()) {
            writeTemplate(configuration, "AiTool.java.ftl", tool.templateModel(project),
                    outputDir.resolve("src/main/java/" + project.basePackagePath() + "/tools/" + tool.className() + ".java"));
        }

        return outputDir;
    }

    private void prepareOutputDirectory(Path outputDir) {
        deleteIfExists(outputDir.resolve("pom.xml"));
        deleteTree(outputDir.resolve("src/main/java"));
        deleteTree(outputDir.resolve("src/main/resources"));
    }

    private Configuration createConfiguration(Path templateDir) {
        if (!Files.isDirectory(templateDir)) {
            throw new IllegalArgumentException("Template directory does not exist: " + templateDir);
        }

        try {
            Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
            configuration.setDefaultEncoding("UTF-8");
            configuration.setDirectoryForTemplateLoading(templateDir.toFile());
            return configuration;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to configure templates: " + templateDir, ex);
        }
    }

    private void writeTemplate(Configuration configuration, String templateName, Map<String, Object> model, Path targetPath) {
        try {
            Template template = configuration.getTemplate(templateName);
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            try (StringWriter writer = new StringWriter()) {
                template.process(model, writer);
                Files.writeString(targetPath, writer.toString());
            }
        } catch (IOException | TemplateException ex) {
            throw new IllegalStateException("Failed to render template " + templateName + " to " + targetPath, ex);
        }
    }

    private void deleteTree(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(this::deleteIfExists);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to clean generated path: " + path, ex);
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete generated path: " + path, ex);
        }
    }

    private record GeneratedProject(
            String artifactId,
            String projectSlug,
            String projectClassName,
            String packageSuffix,
            String basePackage,
            String basePackagePath,
            String clientClassName,
            String clientFieldName,
            String configPrefix,
            String baseUrl,
            String contextPath,
            List<GeneratedTool> tools
    ) {
        private static GeneratedProject from(ToolManifest manifest) {
            String projectSlug = slugify(manifest.project().name());
            String projectClassName = toPascalCase(manifest.project().name());
            String packageSuffix = projectSlug.replace("-", "");
            String basePackage = "com.enterprise.ai.skill.generated." + packageSuffix;
            List<GeneratedTool> tools = manifest.tools().stream()
                    .map(GeneratedTool::from)
                    .toList();
            return new GeneratedProject(
                    "skill-" + projectSlug,
                    projectSlug,
                    projectClassName,
                    packageSuffix,
                    basePackage,
                    basePackage.replace('.', '/'),
                    projectClassName + "Client",
                    toCamelCase(projectClassName + "Client"),
                    "skill." + projectSlug + ".base-url",
                    manifest.project().baseUrl(),
                    manifest.project().contextPath(),
                    tools
            );
        }

        private Map<String, Object> templateModel() {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("artifactId", artifactId);
            model.put("projectSlug", projectSlug);
            model.put("projectClassName", projectClassName);
            model.put("basePackage", basePackage);
            model.put("clientClassName", clientClassName);
            model.put("clientFieldName", clientFieldName);
            model.put("configPrefix", configPrefix);
            model.put("baseUrl", baseUrl);
            model.put("contextPath", contextPath);
            return model;
        }
    }

    private record GeneratedTool(
            String toolName,
            String className,
            String description,
            String method,
            String path,
            String responseType,
            List<GeneratedParameter> allParameters,
            List<GeneratedParameter> pathParameters,
            List<GeneratedParameter> queryParameters,
            GeneratedParameter bodyParameter
    ) {
        private static GeneratedTool from(ToolDefinition definition) {
            List<GeneratedParameter> allParameters = definition.parameters().stream()
                    .map(GeneratedParameter::from)
                    .toList();
            List<GeneratedParameter> pathParameters = allParameters.stream()
                    .filter(parameter -> parameter.location().equals("PATH"))
                    .toList();
            List<GeneratedParameter> queryParameters = allParameters.stream()
                    .filter(parameter -> parameter.location().equals("QUERY"))
                    .toList();
            GeneratedParameter bodyParameter = allParameters.stream()
                    .filter(parameter -> parameter.location().equals("BODY"))
                    .findFirst()
                    .orElse(null);
            return new GeneratedTool(
                    definition.name(),
                    toPascalCase(definition.name()) + "Tool",
                    definition.description(),
                    definition.method(),
                    definition.path(),
                    definition.responseType(),
                    allParameters,
                    pathParameters,
                    queryParameters,
                    bodyParameter
            );
        }

        private Map<String, Object> templateModel(GeneratedProject project) {
            Map<String, Object> model = new LinkedHashMap<>(project.templateModel());
            model.put("toolName", toolName);
            model.put("toolClassName", className);
            model.put("toolDescription", description);
            model.put("httpMethod", method);
            model.put("path", path);
            model.put("responseType", responseType);
            model.put("allParameters", allParameters.stream().map(GeneratedParameter::templateModel).toList());
            model.put("pathParameters", pathParameters.stream().map(GeneratedParameter::templateModel).toList());
            model.put("queryParameters", queryParameters.stream().map(GeneratedParameter::templateModel).toList());
            model.put("bodyParameter", bodyParameter == null ? null : bodyParameter.templateModel());
            model.put("hasBodyParameter", bodyParameter != null);
            return model;
        }
    }

    private record GeneratedParameter(
            String name,
            String type,
            String description,
            boolean required,
            String location
    ) {
        private static GeneratedParameter from(ToolParameterDefinition definition) {
            return new GeneratedParameter(
                    definition.name(),
                    definition.type(),
                    definition.description(),
                    definition.required(),
                    definition.location().name()
            );
        }

        private Map<String, Object> templateModel() {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("name", name);
            model.put("type", type);
            model.put("description", description);
            model.put("required", required);
            model.put("location", location);
            return model;
        }
    }

    private static String slugify(String rawValue) {
        return rawValue
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String toPascalCase(String rawValue) {
        List<String> parts = new ArrayList<>();
        String normalized = rawValue
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_");
        for (String part : normalized.split("_")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            parts.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1).toLowerCase(Locale.ROOT));
        }
        return String.join("", parts);
    }

    private static String toCamelCase(String rawValue) {
        String pascalCase = toPascalCase(rawValue);
        if (pascalCase.isEmpty()) {
            return pascalCase;
        }
        return pascalCase.substring(0, 1).toLowerCase(Locale.ROOT) + pascalCase.substring(1);
    }
}
