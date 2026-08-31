package com.enterprise.ai.runtime.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Operator-owned project source and acceptance policy. Model/user input cannot override it. */
@Component
public class ManagedSandboxPolicyRegistry {

    private final Map<String, GitWorkspaceSource> workspaceSources;
    private final Map<String, List<AcceptanceCommand>> acceptanceProfiles;
    private final ObjectMapper objectMapper;

    public ManagedSandboxPolicyRegistry(
            ObjectMapper objectMapper,
            @Value("${reachai.runtime.managed-executor.workspace-sources-json:{}}") String workspaceSourcesJson,
            @Value("${reachai.runtime.managed-executor.acceptance-profiles-json:{}}") String acceptanceProfilesJson) {
        this.objectMapper = objectMapper;
        this.workspaceSources = parseSources(workspaceSourcesJson);
        this.acceptanceProfiles = parseProfiles(acceptanceProfilesJson);
    }

    public GitWorkspaceSource requireWorkspaceSource(String projectCode) {
        String key = identifier(projectCode, "projectCode", 128).toUpperCase(Locale.ROOT);
        GitWorkspaceSource source = workspaceSources.get(key);
        if (source == null) {
            throw new ManagedExecutionException(503, "MANAGED_WORKSPACE_SOURCE_UNAVAILABLE",
                    "Managed Executor workspace source is not configured");
        }
        return source;
    }

    public List<AcceptanceCommand> requireAcceptanceProfile(String profile) {
        String key = identifier(profile, "acceptanceProfile", 128).toUpperCase(Locale.ROOT);
        List<AcceptanceCommand> commands = acceptanceProfiles.get(key);
        if (commands == null) {
            throw new ManagedExecutionException(503, "MANAGED_ACCEPTANCE_PROFILE_UNAVAILABLE",
                    "Managed Executor acceptance profile is not configured");
        }
        return commands;
    }

    public String acceptanceCommandsJson(String profile) {
        try {
            return objectMapper.writeValueAsString(requireAcceptanceProfile(profile));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Managed acceptance profile serialization failed", impossible);
        }
    }

    public String acceptanceCommandsJson(String projectCode, String profile) {
        String projectKey = identifier(projectCode, "projectCode", 128).toUpperCase(Locale.ROOT);
        String profileKey = identifier(profile, "acceptanceProfile", 128).toUpperCase(Locale.ROOT);
        List<AcceptanceCommand> commands = acceptanceProfiles.get(projectKey + ":" + profileKey);
        if (commands == null) commands = acceptanceProfiles.get(profileKey);
        if (commands == null) {
            throw new ManagedExecutionException(503, "MANAGED_ACCEPTANCE_PROFILE_UNAVAILABLE",
                    "Managed Executor acceptance profile is not configured");
        }
        try {
            return objectMapper.writeValueAsString(commands);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Managed acceptance profile serialization failed", impossible);
        }
    }

    private Map<String, GitWorkspaceSource> parseSources(String value) {
        JsonNode root = parseObject(value, "workspace-sources-json");
        Map<String, GitWorkspaceSource> result = new HashMap<>();
        root.fields().forEachRemaining(entry -> {
            String project = identifier(entry.getKey(), "workspace source project", 128).toUpperCase(Locale.ROOT);
            JsonNode source = entry.getValue();
            if (!source.isObject() || !"GIT".equals(text(source, "type").toUpperCase(Locale.ROOT))) {
                throw invalidConfiguration("Managed workspace source type must be GIT");
            }
            String repositoryUrl = httpsRepositoryUrl(text(source, "repositoryUrl"));
            String revision = text(source, "revision").toLowerCase(Locale.ROOT);
            if (!revision.matches("[a-f0-9]{40,64}")) {
                throw invalidConfiguration("Managed workspace revision must be an immutable commit SHA");
            }
            String credentialSecretName = optionalDnsLabel(text(source, "credentialSecretName"));
            result.put(project, new GitWorkspaceSource(repositoryUrl, revision, credentialSecretName));
        });
        return Map.copyOf(result);
    }

    private Map<String, List<AcceptanceCommand>> parseProfiles(String value) {
        JsonNode root = parseObject(value, "acceptance-profiles-json");
        Map<String, List<AcceptanceCommand>> result = new HashMap<>();
        root.fields().forEachRemaining(entry -> {
            String profile = identifier(entry.getKey(), "acceptance profile", 128).toUpperCase(Locale.ROOT);
            JsonNode commands = entry.getValue();
            if (!commands.isArray() || commands.size() > 20) {
                throw invalidConfiguration("Managed acceptance profile must contain at most 20 commands");
            }
            List<AcceptanceCommand> parsed = new ArrayList<>();
            for (JsonNode command : commands) {
                if (!command.isObject()) throw invalidConfiguration("Managed acceptance command must be an object");
                String name = requiredText(text(command, "name"), "acceptance command name", 128);
                JsonNode argvNode = command.path("argv");
                if (!argvNode.isArray() || argvNode.isEmpty() || argvNode.size() > 64) {
                    throw invalidConfiguration("Managed acceptance argv must contain 1 to 64 values");
                }
                List<String> argv = new ArrayList<>();
                for (JsonNode argument : argvNode) {
                    if (!argument.isTextual()) throw invalidConfiguration("Managed acceptance argv must be text");
                    argv.add(requiredText(argument.textValue(), "acceptance argument", 2_000));
                }
                int timeoutMs = command.path("timeoutMs").asInt(-1);
                if (timeoutMs < 1_000 || timeoutMs > 3_600_000) {
                    throw invalidConfiguration("Managed acceptance timeout is invalid");
                }
                parsed.add(new AcceptanceCommand(name, List.copyOf(argv), timeoutMs));
            }
            result.put(profile, List.copyOf(parsed));
        });
        return Map.copyOf(result);
    }

    private JsonNode parseObject(String value, String field) {
        try {
            JsonNode root = objectMapper.readTree(StringUtils.hasText(value) ? value : "{}");
            if (root == null || !root.isObject()) throw invalidConfiguration(field + " must be a JSON object");
            return root;
        } catch (java.io.IOException failure) {
            throw invalidConfiguration(field + " is invalid JSON");
        }
    }

    private String httpsRepositoryUrl(String value) {
        try {
            URI uri = URI.create(requiredText(value, "repositoryUrl", 1_000));
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw invalidConfiguration("Managed repository URL must be credential-free HTTPS");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException failure) {
            throw invalidConfiguration("Managed repository URL is invalid");
        }
    }

    private String optionalDnsLabel(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 63 || !normalized.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw invalidConfiguration("Managed Git credential Secret name is invalid");
        }
        return normalized;
    }

    private String identifier(String value, String field, int maximum) {
        String result = requiredText(value, field, maximum);
        if (!result.matches("[A-Za-z0-9._:-]+")) throw invalidConfiguration(field + " is invalid");
        return result;
    }

    private String requiredText(String value, String field, int maximum) {
        if (!StringUtils.hasText(value) || value.length() > maximum || value.indexOf('\0') >= 0) {
            throw invalidConfiguration(field + " is invalid");
        }
        return value.trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private IllegalStateException invalidConfiguration(String message) {
        return new IllegalStateException(message);
    }

    public record GitWorkspaceSource(String repositoryUrl, String revision, String credentialSecretName) {
    }

    public record AcceptanceCommand(String name, List<String> argv, int timeoutMs) {
    }
}
