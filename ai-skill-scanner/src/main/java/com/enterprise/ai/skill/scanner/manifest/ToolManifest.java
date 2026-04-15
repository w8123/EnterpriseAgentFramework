package com.enterprise.ai.skill.scanner.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * scanner 与 generator 之间共享的统一契约。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolManifest(
        ProjectMetadata project,
        List<ToolDefinition> tools
) {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .build()
    );

    public ToolManifest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public void validate() {
        require(project != null, "project is required");
        require(notBlank(project.name()), "project.name is required");
        require(notBlank(project.baseUrl()), "project.baseUrl is required");
        require(project.contextPath() != null, "project.contextPath is required");

        Set<String> toolNames = new HashSet<>();
        for (ToolDefinition tool : tools) {
            require(tool != null, "tool is required");
            require(notBlank(tool.name()), "tool.name is required");
            require(notBlank(tool.method()), "tool.method is required");
            require(notBlank(tool.path()), "tool.path is required");
            require(notBlank(tool.endpoint()), "tool.endpoint is required");
            require(toolNames.add(tool.name()), "duplicate tool.name: " + tool.name());

            for (ToolParameterDefinition parameter : tool.parameters()) {
                require(parameter != null, "tool.parameter is required");
                require(notBlank(parameter.name()), "tool.parameter.name is required");
                require(notBlank(parameter.type()), "tool.parameter.type is required");
                require(parameter.location() != null, "tool.parameter.location is required");
            }
        }
    }

    public String toYaml() {
        validate();
        try {
            return YAML_MAPPER.writeValueAsString(this);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize ToolManifest to YAML", ex);
        }
    }

    public static ToolManifest fromYaml(String yaml) {
        try {
            ToolManifest manifest = YAML_MAPPER.readValue(yaml, ToolManifest.class);
            manifest.validate();
            return manifest;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse ToolManifest YAML", ex);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
