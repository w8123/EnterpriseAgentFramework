package com.enterprise.ai.skill.scanner.cli;

import com.enterprise.ai.skill.scanner.controller.ControllerAnnotationToolManifestScanner;
import com.enterprise.ai.skill.scanner.generator.SkillServiceProjectGenerator;
import com.enterprise.ai.skill.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.skill.scanner.manifest.ToolManifest;
import com.enterprise.ai.skill.scanner.openapi.OpenApiToolManifestScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * scanner-first 工作流的命令行入口。
 */
public class SkillScannerCli {

    private final OpenApiToolManifestScanner openApiScanner = new OpenApiToolManifestScanner();
    private final ControllerAnnotationToolManifestScanner controllerScanner = new ControllerAnnotationToolManifestScanner();
    private final SkillServiceProjectGenerator generator = new SkillServiceProjectGenerator();

    public static void main(String[] args) {
        int exitCode = new SkillScannerCli().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public int run(String[] args) {
        if (args == null || args.length == 0) {
            return fail("Usage: scan-openapi|scan-controller|generate [options]");
        }

        try {
            String command = args[0];
            Map<String, String> options = parseOptions(args);
            return switch (command) {
                case "scan-openapi" -> runOpenApiScan(options);
                case "scan-controller" -> runControllerScan(options);
                case "generate" -> runGenerate(options);
                default -> fail("Unknown command: " + command);
            };
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            return 1;
        }
    }

    private int runOpenApiScan(Map<String, String> options) throws Exception {
        Path spec = requiredPath(options, "--spec");
        Path output = requiredPath(options, "--output");
        ToolManifest manifest = openApiScanner.scan(spec, projectMetadata(options));
        writeManifest(output, manifest);
        return 0;
    }

    private int runControllerScan(Map<String, String> options) throws Exception {
        Path source = requiredPath(options, "--source");
        Path output = requiredPath(options, "--output");
        ToolManifest manifest = controllerScanner.scan(source, projectMetadata(options));
        writeManifest(output, manifest);
        return 0;
    }

    private int runGenerate(Map<String, String> options) throws Exception {
        Path manifestPath = requiredPath(options, "--manifest");
        Path templateDir = requiredPath(options, "--template-dir");
        Path outputDir = requiredPath(options, "--output-dir");
        ToolManifest manifest = ToolManifest.fromYaml(Files.readString(manifestPath));
        generator.generate(manifest, outputDir, templateDir);
        return 0;
    }

    private void writeManifest(Path output, ToolManifest manifest) throws Exception {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, manifest.toYaml());
    }

    private ProjectMetadata projectMetadata(Map<String, String> options) {
        return new ProjectMetadata(
                requiredOption(options, "--project-name"),
                requiredOption(options, "--base-url"),
                requiredOption(options, "--context-path")
        );
    }

    private Path requiredPath(Map<String, String> options, String key) {
        return Path.of(requiredOption(options, key));
    }

    private String requiredOption(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option: " + key);
        }
        return value;
    }

    private Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for option: " + args[index]);
            }
            options.put(args[index], args[index + 1]);
        }
        return options;
    }

    private int fail(String message) {
        System.err.println(message);
        return 1;
    }
}
