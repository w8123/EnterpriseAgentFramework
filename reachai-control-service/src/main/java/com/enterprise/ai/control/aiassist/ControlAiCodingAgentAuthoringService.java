package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.agentskill.AgentSkillAccessPolicy;
import com.enterprise.ai.control.agentskill.AgentSkillBindingSnapshotAssembler;
import com.enterprise.ai.control.agentskill.AgentSkillCatalogService;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.BindingDescriptor;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.agentskill.AgentSkillException;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ControlAiCodingAgentAuthoringService {

    private static final Pattern KEY_SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,127}");
    private static final Set<String> CREATE_FIELDS = Set.of(
            "keySlug", "name", "description", "enabled", "allowedRoles",
            "supervisorConfig", "activate", "requestedBy");
    private static final Set<String> INITIAL_CONFIG_FIELDS = Set.of(
            "systemPrompt", "modelInstanceId", "maxPlanSteps", "maxWorkflowCalls",
            "maxReplans", "totalTimeoutMs", "workflowTimeoutMs", "pageBridgeTimeoutMs",
            "parallelReadOnly", "policyProfile", "config");
    private static final Set<String> IDENTITY_FIELDS = Set.of(
            "name", "description", "enabled", "allowedRoles");
    private static final Set<String> CONFIG_FIELDS = Set.of(
            "baseConfigVersionId", "systemPrompt", "modelInstanceId",
            "maxPlanSteps", "maxWorkflowCalls", "maxReplans", "totalTimeoutMs",
            "workflowTimeoutMs", "pageBridgeTimeoutMs", "parallelReadOnly",
            "policyProfile", "config");
    private static final Set<String> PUBLISH_FIELDS = Set.of(
            "configVersionId", "requestedBy");
    private static final Set<String> SKILL_ATTACH_FIELDS = Set.of(
            "skillId", "skillVersionId", "baseConfigVersionId", "activationMode",
            "required", "enabled", "publish", "requestedBy");
    private static final Set<String> SKILL_DETACH_FIELDS = Set.of(
            "skillId", "baseConfigVersionId", "publish", "requestedBy");

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final RuntimeProxyClient runtimeClient;
    private final ControlProjectAgentProvisioningService provisioningService;
    private final AgentSkillCatalogService skillCatalogService;
    private final AgentSkillAccessPolicy skillAccessPolicy;
    private final AgentSkillBindingSnapshotAssembler skillSnapshotAssembler;
    private final ObjectMapper objectMapper;

    public Map<String, Object> listAgents(Long projectId) {
        ProjectRef project = resolveProject(projectId);
        List<Map<String, Object>> agents = responseList(callRuntime(
                () -> runtimeClient.listAgents(project.id(), project.code()),
                "AGENT_LIST_FAILED", "Runtime Agent list failed"));
        return ordered(
                "schema", "agent-ai-coding-list.v1",
                "project", project.toMap(),
                "agents", agents.stream().filter(agent -> belongsToProject(agent, project)).toList());
    }

    public Map<String, Object> getAgent(Long projectId, String agentId) {
        ProjectRef project = resolveProject(projectId);
        Map<String, Object> agent = requireAgent(project, agentId);
        List<Map<String, Object>> configs = configVersions(agentId(agent));
        return ordered(
                "schema", "agent-ai-coding-context.v1",
                "project", project.toMap(),
                "agent", agent,
                "configVersions", configs,
                "mutableBase", mutableBaseView(configs));
    }

    public Map<String, Object> createAgent(Long projectId, Map<String, ?> request) {
        ProjectRef project = resolveProject(projectId);
        Map<String, ?> input = request == null ? Map.of() : request;
        rejectUnknownFields(input, CREATE_FIELDS, "Agent create request");
        String keySlug = requiredText(input.get("keySlug"), "keySlug");
        if (!KEY_SLUG.matcher(keySlug).matches()) {
            throw invalid("AGENT_KEY_INVALID",
                    "keySlug must match [A-Za-z0-9][A-Za-z0-9_-]{1,127}");
        }
        String name = requiredText(input.get("name"), "name");
        Map<String, Object> supervisorInput = mapValue(input.get("supervisorConfig"), "supervisorConfig");
        rejectUnknownFields(supervisorInput, INITIAL_CONFIG_FIELDS, "Initial Agent Supervisor config");
        String modelInstanceId = resolveSupervisorModelInstanceId(
                optionalText(supervisorInput.get("modelInstanceId")));
        Map<String, Object> draftRequest = initialSupervisorConfig(
                name, keySlug, modelInstanceId, supervisorInput);
        List<Map<String, Object>> existing = responseList(callRuntime(
                () -> runtimeClient.listAgents(project.id(), project.code()),
                "AGENT_LIST_FAILED", "Runtime Agent list failed"));
        if (existing.stream().anyMatch(agent -> keySlug.equalsIgnoreCase(text(agent.get("keySlug"))))) {
            throw new ControlAiCodingAgentException(
                    "AGENT_KEY_CONFLICT",
                    "An Agent with the same keySlug already exists in this project: " + keySlug,
                    HttpStatus.CONFLICT,
                    Map.of("keySlug", keySlug));
        }

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("projectId", project.id());
        identity.put("projectCode", project.code());
        identity.put("keySlug", keySlug);
        identity.put("name", name);
        identity.put("description", optionalText(input.get("description")));
        identity.put("visibility", "PROJECT");
        identity.put("enabled", booleanValue(input.get("enabled"), true, "enabled"));
        if (input.containsKey("allowedRoles")) {
            identity.put("allowedRolesJson", json(stringList(input.get("allowedRoles"), "allowedRoles", 64, 128)));
        }

        Map<String, Object> agent = responseMap(callRuntime(
                () -> runtimeClient.createAgent(identity),
                "AGENT_CREATE_FAILED", "Runtime Agent creation failed"));
        requireBelongsToProject(agent, project);
        String createdAgentId = agentId(agent);
        Map<String, Object> draft = responseMap(callRuntime(
                () -> runtimeClient.saveAgentConfigDraft(createdAgentId, draftRequest),
                "AGENT_CONFIG_SAVE_FAILED", "Initial Agent Supervisor config could not be saved"));
        boolean activate = booleanValue(input.get("activate"), true, "activate");
        Map<String, Object> config = activate
                ? publishRuntimeConfig(createdAgentId, draft, requestedBy(input))
                : draft;
        return ordered(
                "schema", "agent-ai-coding-create.v1",
                "project", project.toMap(),
                "agent", agent,
                "supervisorConfig", config,
                "activated", activate);
    }

    public Map<String, Object> updateAgentIdentity(
            Long projectId,
            String agentId,
            Map<String, ?> request) {
        ProjectRef project = resolveProject(projectId);
        Map<String, Object> current = requireAgent(project, agentId);
        Map<String, ?> input = request == null ? Map.of() : request;
        rejectUnknownFields(input, IDENTITY_FIELDS, "Agent identity");
        Map<String, Object> update = new LinkedHashMap<>();
        if (input.containsKey("name")) update.put("name", requiredText(input.get("name"), "name"));
        if (input.containsKey("description")) update.put("description", stringOrNull(input.get("description"), "description"));
        if (input.containsKey("enabled")) update.put("enabled", booleanValue(input.get("enabled"), true, "enabled"));
        if (input.containsKey("allowedRoles")) {
            update.put("allowedRolesJson", json(stringList(input.get("allowedRoles"), "allowedRoles", 64, 128)));
        }
        if (update.isEmpty()) throw invalid("AGENT_UPDATE_EMPTY", "At least one Agent identity field is required");
        Map<String, Object> updated = responseMap(callRuntime(
                () -> runtimeClient.updateAgent(agentId(current), update),
                "AGENT_UPDATE_FAILED", "Runtime Agent update failed"));
        requireBelongsToProject(updated, project);
        return ordered(
                "schema", "agent-ai-coding-update.v1",
                "project", project.toMap(),
                "agent", updated);
    }

    public Map<String, Object> saveConfigDraft(
            Long projectId,
            String agentId,
            Map<String, ?> request) {
        ProjectRef project = resolveProject(projectId);
        Map<String, Object> agent = requireAgent(project, agentId);
        Map<String, ?> input = request == null ? Map.of() : request;
        rejectUnknownFields(input, CONFIG_FIELDS, "Agent Supervisor config");
        List<Map<String, Object>> versions = configVersions(agentId(agent));
        Map<String, Object> base = requireMutableBase(
                versions, nullableLong(input.get("baseConfigVersionId"), "baseConfigVersionId"), true);
        Map<String, Object> update = supervisorConfigUpdate(input);
        if (update.isEmpty()) {
            throw invalid("AGENT_CONFIG_UPDATE_EMPTY", "At least one Supervisor config field is required");
        }
        Map<String, Object> draft = responseMap(callRuntime(
                () -> runtimeClient.saveAgentConfigDraft(agentId(agent), update),
                "AGENT_CONFIG_SAVE_FAILED", "Agent Supervisor config draft could not be saved"));
        return ordered(
                "schema", "agent-ai-coding-config-draft.v1",
                "project", project.toMap(),
                "agent", agent,
                "baseConfigVersionId", longValue(base.get("id")),
                "draft", draft);
    }

    public Map<String, Object> publishConfig(
            Long projectId,
            String agentId,
            Map<String, ?> request) {
        ProjectRef project = resolveProject(projectId);
        Map<String, Object> agent = requireAgent(project, agentId);
        Map<String, ?> input = request == null ? Map.of() : request;
        rejectUnknownFields(input, PUBLISH_FIELDS, "Agent config publish request");
        Long configVersionId = requiredLong(input.get("configVersionId"), "configVersionId");
        Map<String, Object> target = configVersions(agentId(agent)).stream()
                .filter(config -> Objects.equals(configVersionId, longValue(config.get("id"))))
                .findFirst()
                .orElseThrow(() -> new ControlAiCodingAgentException(
                        "AGENT_CONFIG_NOT_FOUND", "Agent config version was not found: " + configVersionId,
                        HttpStatus.NOT_FOUND));
        if (!"DRAFT".equalsIgnoreCase(text(target.get("status")))) {
            throw new ControlAiCodingAgentException(
                    "AGENT_CONFIG_NOT_DRAFT", "Only a DRAFT Agent config can be published",
                    HttpStatus.CONFLICT,
                    Map.of("configVersionId", configVersionId, "status", String.valueOf(target.get("status"))));
        }
        Map<String, Object> published = publishAttestedConfig(
                project, agentId(agent), target, requestedBy(input));
        return ordered(
                "schema", "agent-ai-coding-config-publish.v1",
                "project", project.toMap(),
                "agent", agent,
                "activeConfig", published);
    }

    public Map<String, Object> listBindableSkills(Long projectId, String search) {
        ProjectRef project = resolveProject(projectId);
        List<Map<String, Object>> bindings = new ArrayList<>();
        try {
            for (SkillSummary skill : skillCatalogService.list(
                    search, AgentSkillCatalogService.STATUS_PUBLISHED)) {
                if (!skillAccessPolicy.canBindWithProjectCredential(skill, project.code())) continue;
                SkillDetail detail = skillCatalogService.detail(skill.id());
                detail.versions().stream()
                        .filter(version -> AgentSkillCatalogService.STATUS_PUBLISHED.equals(version.status()))
                        .map(version -> new BindingDescriptor(skill, version))
                        .map(this::bindingView)
                        .forEach(bindings::add);
            }
        } catch (AgentSkillException ex) {
            throw skillError(ex);
        }
        return ordered(
                "schema", "agent-ai-coding-bindable-skills.v1",
                "project", project.toMap(),
                "bindings", List.copyOf(bindings),
                "scriptPolicy", "DENY");
    }

    public Map<String, Object> attachSkill(
            Long projectId,
            String agentId,
            Map<String, ?> request) {
        ProjectRef project = resolveProject(projectId);
        Map<String, Object> agent = requireAgent(project, agentId);
        Map<String, ?> input = request == null ? Map.of() : request;
        rejectUnknownFields(input, SKILL_ATTACH_FIELDS, "Agent Skill attach request");
        Long skillId = requiredLong(input.get("skillId"), "skillId");
        Long skillVersionId = requiredLong(input.get("skillVersionId"), "skillVersionId");
        BindingDescriptor descriptor;
        try {
            descriptor = skillCatalogService.publishedBindingDescriptor(skillId, skillVersionId);
            skillAccessPolicy.requireProjectCredentialBind(descriptor.skill(), project.code());
        } catch (AgentSkillException ex) {
            throw skillError(ex);
        }

        List<Map<String, Object>> versions = configVersions(agentId(agent));
        Map<String, Object> base = requireMutableBase(
                versions, nullableLong(input.get("baseConfigVersionId"), "baseConfigVersionId"), true);
        List<Map<String, Object>> selections = skillSelections(base.get("skills"));
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("skillId", skillId);
        requested.put("skillVersionId", skillVersionId);
        requested.put("activationMode", firstText(optionalText(input.get("activationMode")), "MODEL_SELECTED"));
        requested.put("scriptPolicy", "DENY");
        requested.put("required", booleanValue(input.get("required"), false, "required"));
        requested.put("enabled", booleanValue(input.get("enabled"), true, "enabled"));

        boolean replaced = false;
        boolean changed = true;
        for (int index = 0; index < selections.size(); index++) {
            Map<String, Object> current = selections.get(index);
            if (Objects.equals(skillId, longValue(current.get("skillId")))) {
                changed = !sameSkillSelection(current, requested);
                selections.set(index, requested);
                replaced = true;
                break;
            }
        }
        if (!replaced) selections.add(requested);
        if (!changed && "ACTIVE".equalsIgnoreCase(text(base.get("status")))) {
            return skillMutationResult(project, agent, base, descriptor, false, true, false);
        }

        Map<String, Object> canonical;
        try {
            canonical = skillSnapshotAssembler.assembleForAiCodingProject(
                    Map.of("skills", selections), project.code());
        } catch (AgentSkillException ex) {
            throw skillError(ex);
        }
        Map<String, Object> draft = responseMap(callRuntime(
                () -> runtimeClient.saveAgentConfigDraft(agentId(agent), canonical),
                "AGENT_SKILL_SAVE_FAILED", "Agent Skill binding draft could not be saved"));
        boolean publish = booleanValue(input.get("publish"), true, "publish");
        Map<String, Object> result = publish
                ? publishAttestedConfig(project, agentId(agent), draft, requestedBy(input))
                : draft;
        return skillMutationResult(project, agent, result, descriptor, !replaced, false, publish);
    }

    public Map<String, Object> detachSkill(
            Long projectId,
            String agentId,
            Map<String, ?> request) {
        ProjectRef project = resolveProject(projectId);
        Map<String, Object> agent = requireAgent(project, agentId);
        Map<String, ?> input = request == null ? Map.of() : request;
        rejectUnknownFields(input, SKILL_DETACH_FIELDS, "Agent Skill detach request");
        Long skillId = requiredLong(input.get("skillId"), "skillId");
        List<Map<String, Object>> versions = configVersions(agentId(agent));
        Map<String, Object> base = requireMutableBase(
                versions, nullableLong(input.get("baseConfigVersionId"), "baseConfigVersionId"), true);
        List<Map<String, Object>> selections = skillSelections(base.get("skills"));
        boolean removed = selections.removeIf(selection -> Objects.equals(
                skillId, longValue(selection.get("skillId"))));
        if (!removed) {
            return ordered(
                    "schema", "agent-skill-detachment.v1",
                    "project", project.toMap(),
                    "agent", agent,
                    "config", base,
                    "skillId", skillId,
                    "removed", false,
                    "reused", true,
                    "published", false);
        }
        Map<String, Object> canonical;
        try {
            canonical = skillSnapshotAssembler.assembleForAiCodingProject(
                    Map.of("skills", selections), project.code());
        } catch (AgentSkillException ex) {
            throw skillError(ex);
        }
        Map<String, Object> draft = responseMap(callRuntime(
                () -> runtimeClient.saveAgentConfigDraft(agentId(agent), canonical),
                "AGENT_SKILL_SAVE_FAILED", "Agent Skill detachment draft could not be saved"));
        boolean publish = booleanValue(input.get("publish"), true, "publish");
        Map<String, Object> result = publish
                ? publishAttestedConfig(project, agentId(agent), draft, requestedBy(input))
                : draft;
        return ordered(
                "schema", "agent-skill-detachment.v1",
                "project", project.toMap(),
                "agent", agent,
                "config", result,
                "skillId", skillId,
                "removed", true,
                "reused", false,
                "published", publish);
    }

    private Map<String, Object> initialSupervisorConfig(
            String agentName,
            String keySlug,
            String modelInstanceId,
            Map<String, Object> input) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("runtimeType", "AGENTSCOPE");
        config.put("systemPrompt", firstText(optionalText(input.get("systemPrompt")),
                "你是“" + agentName + "”。理解用户意图，从允许的 Skill 和 Workflow 中选择合适能力完成任务。"));
        config.put("modelInstanceId", modelInstanceId);
        copyConfigValues(input, config);
        String policyProfile = firstText(optionalText(input.get("policyProfile")), "DEV_ALLOW_ALL");
        if (!"DEV_ALLOW_ALL".equals(policyProfile)) {
            throw invalid("AGENT_POLICY_NOT_SUPPORTED",
                    "AI Coding currently supports policyProfile=DEV_ALLOW_ALL only");
        }
        config.put("policyProfile", policyProfile);
        config.put("toolCatalogMode", "ALLOW_LIST");
        Object extra = input.get("config");
        config.put("configJson", extra == null
                ? json(Map.of("source", "agent-ai-coding", "agentKeySlug", keySlug))
                : json(mapValue(extra, "supervisorConfig.config")));
        config.put("tools", List.of());
        config.put("skills", List.of());
        config.put("remoteAgents", List.of());
        return config;
    }

    private Map<String, Object> supervisorConfigUpdate(Map<String, ?> input) {
        Map<String, Object> update = new LinkedHashMap<>();
        if (input.containsKey("systemPrompt")) {
            update.put("systemPrompt", stringOrNull(input.get("systemPrompt"), "systemPrompt"));
        }
        if (input.containsKey("modelInstanceId")) {
            update.put("modelInstanceId", resolveSupervisorModelInstanceId(
                    requiredText(input.get("modelInstanceId"), "modelInstanceId")));
        }
        copyConfigValues(input, update);
        if (input.containsKey("policyProfile")) {
            String policy = requiredText(input.get("policyProfile"), "policyProfile");
            if (!"DEV_ALLOW_ALL".equals(policy)) {
                throw invalid("AGENT_POLICY_NOT_SUPPORTED",
                        "AI Coding currently supports policyProfile=DEV_ALLOW_ALL only");
            }
            update.put("policyProfile", policy);
        }
        if (input.containsKey("config")) {
            update.put("configJson", json(mapValue(input.get("config"), "config")));
        }
        return update;
    }

    private void copyConfigValues(Map<String, ?> source, Map<String, Object> target) {
        for (String field : List.of(
                "maxPlanSteps", "maxWorkflowCalls", "maxReplans", "totalTimeoutMs",
                "workflowTimeoutMs", "pageBridgeTimeoutMs", "parallelReadOnly")) {
            if (source.containsKey(field)) target.put(field, source.get(field));
        }
    }

    private Map<String, Object> publishAttestedConfig(
            ProjectRef project,
            String agentId,
            Map<String, Object> draft,
            String publishedBy) {
        Object remoteAgents = draft.get("remoteAgents");
        if (remoteAgents instanceof Collection<?> values && !values.isEmpty()) {
            throw new ControlAiCodingAgentException(
                    "AGENT_A2A_REQUIRES_CONSOLE",
                    "AI Coding cannot publish a config containing remote A2A Agent bindings",
                    HttpStatus.CONFLICT);
        }
        Map<String, Object> target = draft;
        Object skills = draft.get("skills");
        if (skills instanceof Collection<?> values && !values.isEmpty()) {
            Map<String, Object> canonical;
            try {
                canonical = skillSnapshotAssembler.assembleForAiCodingProject(
                        Map.of("skills", skillSelections(values)), project.code());
            } catch (AgentSkillException ex) {
                throw skillError(ex);
            }
            target = responseMap(callRuntime(
                    () -> runtimeClient.saveAgentConfigDraft(agentId, canonical),
                    "AGENT_SKILL_REVALIDATION_FAILED",
                    "Agent Skill bindings could not be revalidated before publish"));
        }
        return publishRuntimeConfig(agentId, target, publishedBy);
    }

    private Map<String, Object> publishRuntimeConfig(
            String agentId,
            Map<String, Object> draft,
            String publishedBy) {
        Long configVersionId = requiredLong(draft.get("id"), "configVersionId");
        return responseMap(callRuntime(
                () -> runtimeClient.publishAgentConfigVersion(
                        agentId, configVersionId, Map.of("publishedBy", publishedBy)),
                "AGENT_CONFIG_PUBLISH_FAILED", "Agent config could not be published"));
    }

    private Map<String, Object> skillMutationResult(
            ProjectRef project,
            Map<String, Object> agent,
            Map<String, Object> config,
            BindingDescriptor descriptor,
            boolean created,
            boolean reused,
            boolean published) {
        return ordered(
                "schema", "agent-skill-attachment.v1",
                "project", project.toMap(),
                "agent", agent,
                "config", config,
                "binding", bindingView(descriptor),
                "created", created,
                "reused", reused,
                "published", published,
                "scriptPolicy", "DENY");
    }

    private Map<String, Object> bindingView(BindingDescriptor descriptor) {
        SkillSummary skill = descriptor.skill();
        VersionView version = descriptor.version();
        return ordered(
                "skill", ordered(
                        "id", skill.id(),
                        "publisher", skill.publisher(),
                        "name", skill.name(),
                        "displayName", skill.displayName(),
                        "description", skill.description(),
                        "visibility", skill.visibility()),
                "version", ordered(
                        "id", version.id(),
                        "version", version.version(),
                        "status", version.status(),
                        "sourceType", version.sourceType(),
                        "sourceSha256", version.sourceSha256(),
                        "contentTreeSha256", version.contentTreeSha256(),
                        "hasScripts", version.hasScripts(),
                        "publishedAt", version.publishedAt()),
                "scriptPolicy", "DENY");
    }

    private ProjectRef resolveProject(Long projectId) {
        if (projectId == null || projectId <= 0L) {
            throw invalid("CAPABILITY_PROJECT_INVALID", "projectId must be a positive number");
        }
        Map<String, Object> project;
        try {
            project = capabilityClient.getOnboardingProjectById(projectId);
        } catch (FeignException.NotFound ex) {
            throw new ControlAiCodingAgentException(
                    "CAPABILITY_PROJECT_NOT_FOUND", "Capability project not found: " + projectId,
                    HttpStatus.NOT_FOUND);
        } catch (FeignException ex) {
            throw new ControlAiCodingAgentException(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE", "Capability project lookup failed",
                    HttpStatus.BAD_GATEWAY,
                    Map.of("status", ex.status()));
        }
        Long resolvedId = firstLong(project.get("id"), project.get("projectId"));
        String projectCode = optionalText(project.get("projectCode"));
        if (!Objects.equals(projectId, resolvedId) || !StringUtils.hasText(projectCode)) {
            throw new ControlAiCodingAgentException(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE",
                    "Capability project lookup response is incomplete",
                    HttpStatus.BAD_GATEWAY);
        }
        return new ProjectRef(resolvedId, projectCode, optionalText(project.get("name")));
    }

    private Map<String, Object> requireAgent(ProjectRef project, String agentReference) {
        String reference = requiredText(agentReference, "agentId");
        Map<String, Object> agent = responseMap(callRuntime(
                () -> runtimeClient.getAgent(reference),
                "AGENT_NOT_FOUND", "Agent was not found: " + reference));
        requireBelongsToProject(agent, project);
        return agent;
    }

    private void requireBelongsToProject(Map<String, Object> agent, ProjectRef project) {
        if (!belongsToProject(agent, project)) {
            throw new ControlAiCodingAgentException(
                    "AGENT_NOT_FOUND",
                    "Agent was not found in the current AI Coding project",
                    HttpStatus.NOT_FOUND);
        }
    }

    private boolean belongsToProject(Map<String, Object> agent, ProjectRef project) {
        Long agentProjectId = nullableLong(agent.get("projectId"), "projectId");
        String agentProjectCode = optionalText(agent.get("projectCode"));
        return Objects.equals(project.id(), agentProjectId)
                && project.code().equalsIgnoreCase(firstText(agentProjectCode, ""));
    }

    private List<Map<String, Object>> configVersions(String agentId) {
        return responseList(callRuntime(
                () -> runtimeClient.listAgentConfigVersions(agentId),
                "AGENT_CONFIG_LIST_FAILED", "Agent config versions could not be read"));
    }

    private Map<String, Object> requireMutableBase(
            List<Map<String, Object>> versions,
            Long requestedBaseId,
            boolean requireExisting) {
        Map<String, Object> draft = versions.stream()
                .filter(config -> "DRAFT".equalsIgnoreCase(text(config.get("status"))))
                .findFirst().orElse(null);
        Map<String, Object> active = versions.stream()
                .filter(config -> "ACTIVE".equalsIgnoreCase(text(config.get("status"))))
                .findFirst().orElse(null);
        Map<String, Object> base = draft == null ? active : draft;
        if (base == null) {
            if (!requireExisting) return Map.of();
            throw new ControlAiCodingAgentException(
                    "AGENT_CONFIG_NOT_FOUND",
                    "Agent has no DRAFT or ACTIVE Supervisor config",
                    HttpStatus.CONFLICT);
        }
        Long actualId = longValue(base.get("id"));
        if (draft != null && requestedBaseId == null) {
            throw new ControlAiCodingAgentException(
                    "AGENT_DRAFT_CONFLICT",
                    "Agent already has a DRAFT config; read Agent context and resend its id as baseConfigVersionId",
                    HttpStatus.CONFLICT,
                    Map.of("currentDraftId", actualId));
        }
        if (requestedBaseId != null && !Objects.equals(actualId, requestedBaseId)) {
            throw new ControlAiCodingAgentException(
                    "AGENT_CONFIG_BASE_CONFLICT",
                    "baseConfigVersionId does not match the current mutable config",
                    HttpStatus.CONFLICT,
                    Map.of("requestedBaseConfigVersionId", requestedBaseId,
                            "currentBaseConfigVersionId", actualId));
        }
        return base;
    }

    private Map<String, Object> mutableBaseView(List<Map<String, Object>> versions) {
        Map<String, Object> draft = versions.stream()
                .filter(config -> "DRAFT".equalsIgnoreCase(text(config.get("status"))))
                .findFirst().orElse(null);
        Map<String, Object> active = versions.stream()
                .filter(config -> "ACTIVE".equalsIgnoreCase(text(config.get("status"))))
                .findFirst().orElse(null);
        Map<String, Object> base = draft == null ? active : draft;
        return base == null ? Map.of() : ordered(
                "configVersionId", longValue(base.get("id")),
                "status", text(base.get("status")),
                "requiresExplicitBase", draft != null);
    }

    private List<Map<String, Object>> skillSelections(Object raw) {
        if (!(raw instanceof Collection<?> values)) return new ArrayList<>();
        List<Map<String, Object>> selections = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> selection = new LinkedHashMap<>();
            selection.put("skillId", longValue(map.get("skillId")));
            selection.put("skillVersionId", longValue(map.get("skillVersionId")));
            selection.put("activationMode", firstText(optionalText(map.get("activationMode")), "MODEL_SELECTED"));
            selection.put("scriptPolicy", firstText(optionalText(map.get("scriptPolicy")), "DENY"));
            selection.put("required", Boolean.TRUE.equals(map.get("required")));
            selection.put("enabled", !Boolean.FALSE.equals(map.get("enabled")));
            selections.add(selection);
        }
        return selections;
    }

    private boolean sameSkillSelection(Map<String, Object> left, Map<String, Object> right) {
        return Objects.equals(longValue(left.get("skillId")), longValue(right.get("skillId")))
                && Objects.equals(longValue(left.get("skillVersionId")), longValue(right.get("skillVersionId")))
                && Objects.equals(firstText(optionalText(left.get("activationMode")), "MODEL_SELECTED"),
                firstText(optionalText(right.get("activationMode")), "MODEL_SELECTED"))
                && Objects.equals(firstText(optionalText(left.get("scriptPolicy")), "DENY"),
                firstText(optionalText(right.get("scriptPolicy")), "DENY"))
                && Objects.equals(Boolean.TRUE.equals(left.get("required")), Boolean.TRUE.equals(right.get("required")))
                && Objects.equals(!Boolean.FALSE.equals(left.get("enabled")), !Boolean.FALSE.equals(right.get("enabled")));
    }

    private Object callRuntime(
            Supplier<ResponseEntity<Object>> call,
            String errorCode,
            String errorMessage) {
        try {
            ResponseEntity<Object> response = call.get();
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                int status = response == null ? 502 : response.getStatusCode().value();
                throw new ControlAiCodingAgentException(
                        errorCode, errorMessage,
                        statusOrBadGateway(status),
                        Map.of("status", status));
            }
            return response.getBody();
        } catch (ControlAiCodingAgentException ex) {
            throw ex;
        } catch (FeignException ex) {
            int rawStatus = ex.status();
            HttpStatus status = statusOrBadGateway(rawStatus);
            throw new ControlAiCodingAgentException(
                    errorCode, errorMessage + ": " + firstText(ex.getMessage(), "upstream error"),
                    status,
                    Map.of("status", rawStatus));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseMap(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new ControlAiCodingAgentException(
                "RUNTIME_DEPENDENCY_UNAVAILABLE", "Runtime returned an invalid object response",
                HttpStatus.BAD_GATEWAY);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> responseList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new ControlAiCodingAgentException(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE", "Runtime returned an invalid list response",
                    HttpStatus.BAD_GATEWAY);
        }
        return collection.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList();
    }

    private void rejectUnknownFields(Map<String, ?> input, Set<String> allowed, String label) {
        Set<String> unknown = new LinkedHashSet<>(input.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw invalid("AGENT_FIELD_NOT_WRITABLE",
                    label + " contains unsupported or protected fields: " + unknown);
        }
    }

    private List<String> stringList(Object value, String field, int maxItems, int maxLength) {
        if (!(value instanceof List<?> list) || list.size() > maxItems) {
            throw invalid("AGENT_FIELD_INVALID", field + " must be a bounded array");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : list) {
            String text = requiredText(item, field);
            if (text.length() > maxLength) {
                throw invalid("AGENT_FIELD_INVALID", field + " contains an overlong value");
            }
            values.add(text);
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value, String field) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid("AGENT_FIELD_INVALID", field + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private String agentId(Map<String, Object> agent) {
        return requiredText(agent.get("id"), "agent.id");
    }

    private String requestedBy(Map<String, ?> request) {
        return firstText(optionalText(request.get("requestedBy")), "ai-coding");
    }

    private String resolveSupervisorModelInstanceId(String requestedModelInstanceId) {
        try {
            return provisioningService.resolveSupervisorModelInstanceId(requestedModelInstanceId);
        } catch (IllegalArgumentException ex) {
            throw new ControlAiCodingAgentException(
                    "AGENT_MODEL_NOT_AVAILABLE", ex.getMessage(), HttpStatus.CONFLICT);
        } catch (FeignException ex) {
            throw new ControlAiCodingAgentException(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE",
                    "Model catalog lookup failed",
                    statusOrBadGateway(ex.status()),
                    Map.of("status", ex.status()));
        }
    }

    private HttpStatus statusOrBadGateway(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? HttpStatus.BAD_GATEWAY : resolved;
    }

    private String requiredText(Object value, String field) {
        String text = optionalText(value);
        if (!StringUtils.hasText(text)) {
            throw invalid("AGENT_FIELD_REQUIRED", field + " is required");
        }
        return text;
    }

    private String stringOrNull(Object value, String field) {
        if (value == null) return null;
        if (!(value instanceof String)) {
            throw invalid("AGENT_FIELD_INVALID", field + " must be a string or null");
        }
        return String.valueOf(value);
    }

    private String optionalText(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) return value.trim();
            }
        }
        return null;
    }

    private boolean booleanValue(Object value, boolean fallback, String field) {
        if (value == null) return fallback;
        if (value instanceof Boolean result) return result;
        throw invalid("AGENT_FIELD_INVALID", field + " must be a boolean");
    }

    private Long requiredLong(Object value, String field) {
        Long result = nullableLong(value, field);
        if (result == null || result <= 0L) {
            throw invalid("AGENT_FIELD_REQUIRED", field + " must be a positive number");
        }
        return result;
    }

    private Long nullableLong(Object value, String field) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                throw invalid("AGENT_FIELD_INVALID", field + " must be a number");
            }
        }
        throw invalid("AGENT_FIELD_INVALID", field + " must be a number");
    }

    private Long longValue(Object value) {
        return nullableLong(value, "id");
    }

    private Long firstLong(Object... values) {
        for (Object value : values) {
            Long result = nullableLong(value, "id");
            if (result != null) return result;
        }
        return null;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw invalid("AGENT_FIELD_INVALID", "Agent configuration could not be serialized");
        }
    }

    private ControlAiCodingAgentException skillError(AgentSkillException ex) {
        return new ControlAiCodingAgentException(
                ex.code(), ex.getMessage(), ex.status());
    }

    private ControlAiCodingAgentException invalid(String code, String message) {
        return new ControlAiCodingAgentException(code, message, HttpStatus.BAD_REQUEST);
    }

    private static Map<String, Object> ordered(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record ProjectRef(Long id, String code, String name) {
        Map<String, Object> toMap() {
            return ordered("id", id, "projectCode", code, "name", name);
        }
    }
}
