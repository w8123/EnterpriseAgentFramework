package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.BindingDescriptor;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Replaces browser-supplied Skill metadata with catalog-authoritative published
 * version snapshots before an Agent draft crosses into Runtime.
 */
@Component
@RequiredArgsConstructor
public class AgentSkillBindingSnapshotAssembler {

    private static final String BIND_PERMISSION = "skill:bind";
    private static final String SCRIPT_APPROVE_PERMISSION = "skill:script:approve";
    private static final Set<String> ACTIVATION_MODES = Set.of("MODEL_SELECTED", "ALWAYS", "EXPLICIT");

    private final AgentSkillCatalogService catalogService;
    private final AgentSkillAccessPolicy accessPolicy;
    private final PlatformAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> assemble(HttpServletRequest request, Map<String, Object> draft) {
        return assemble(request, draft, null);
    }

    public Map<String, Object> assemble(HttpServletRequest request,
                                        Map<String, Object> draft,
                                        String agentProjectCode) {
        Map<String, Object> source = draft == null ? Map.of() : draft;
        if (!source.containsKey("skills")) {
            return source;
        }
        PlatformAuthenticatedSession session = requireSession(request);
        accessPolicy.requireAgentBindScope(session, BIND_PERMISSION, agentProjectCode);

        return assembleSelections(source, agentProjectCode, descriptor -> {
            accessPolicy.requireAccess(session, BIND_PERMISSION, descriptor.skill());
            accessPolicy.requireAgentProject(descriptor.skill(), agentProjectCode);
        }, session);
    }

    /**
     * Catalog attestation for project-key AI Coding calls. This deliberately
     * has no HttpServletRequest overload: a project credential is not a human
     * console session and cannot inherit owner or script-approval permissions.
     */
    public Map<String, Object> assembleForAiCodingProject(
            Map<String, Object> draft,
            String agentProjectCode) {
        Map<String, Object> source = draft == null ? Map.of() : draft;
        if (!source.containsKey("skills")) {
            return source;
        }
        return assembleSelections(source, agentProjectCode,
                descriptor -> accessPolicy.requireProjectCredentialBind(
                        descriptor.skill(), agentProjectCode),
                null);
    }

    private Map<String, Object> assembleSelections(
            Map<String, Object> source,
            String agentProjectCode,
            Consumer<BindingDescriptor> accessCheck,
            PlatformAuthenticatedSession session) {

        Object raw = source.get("skills");
        if (raw != null && !(raw instanceof List<?>)) {
            throw new AgentSkillException("SKILL_BINDING_INVALID", HttpStatus.BAD_REQUEST,
                    "Agent config skills must be an array");
        }
        List<?> selections = raw == null ? List.of() : (List<?>) raw;
        if (selections.size() > 64) {
            throw new AgentSkillException("SKILL_BINDING_INVALID", HttpStatus.BAD_REQUEST,
                    "An Agent config cannot bind more than 64 Skills");
        }

        List<Map<String, Object>> snapshots = new ArrayList<>(selections.size());
        Map<String, Boolean> identities = new LinkedHashMap<>();
        int index = 0;
        for (Object value : selections) {
            Selection selection = selection(value);
            BindingDescriptor descriptor = catalogService.publishedBindingDescriptor(
                    selection.skillId(), selection.skillVersionId());
            accessCheck.accept(descriptor);
            VersionView version = descriptor.version();
            String identity = descriptor.skill().publisher() + "/" + descriptor.skill().name();
            if (identities.putIfAbsent(identity, true) != null) {
                throw new AgentSkillException("SKILL_BINDING_INVALID", HttpStatus.BAD_REQUEST,
                        "Duplicate Skill binding: " + identity);
            }

            String activationMode = upperOrDefault(selection.activationMode(), "MODEL_SELECTED");
            if (!ACTIVATION_MODES.contains(activationMode)) {
                throw new AgentSkillException("SKILL_BINDING_INVALID", HttpStatus.BAD_REQUEST,
                        "Skill activationMode must be MODEL_SELECTED, ALWAYS, or EXPLICIT");
            }
            String scriptPolicy = upperOrDefault(selection.scriptPolicy(), "DENY");
            if (!Set.of("DENY", "SANDBOX_REVIEWED").contains(scriptPolicy)) {
                throw new AgentSkillException("SKILL_BINDING_INVALID", HttpStatus.BAD_REQUEST,
                        "Skill scriptPolicy must be DENY or SANDBOX_REVIEWED");
            }
            if (session == null && "SANDBOX_REVIEWED".equals(scriptPolicy)) {
                throw AgentSkillException.forbidden(
                        "AI Coding project credentials cannot approve Skill script execution");
            }
            if (version.hasScripts() && "SANDBOX_REVIEWED".equals(scriptPolicy)) {
                authorizationService.requireGlobalPermission(session, SCRIPT_APPROVE_PERMISSION);
            }

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("skillId", descriptor.skill().id());
            snapshot.put("skillVersionId", version.id());
            snapshot.put("publisher", descriptor.skill().publisher());
            snapshot.put("name", descriptor.skill().name());
            snapshot.put("displayName", descriptor.skill().displayName());
            snapshot.put("visibility", descriptor.skill().visibility());
            snapshot.put("projectCode", descriptor.skill().projectCode());
            snapshot.put("version", version.version());
            snapshot.put("catalogStatus", version.status());
            snapshot.put("sourceSha256", version.sourceSha256());
            snapshot.put("contentTreeSha256", version.contentTreeSha256());
            snapshot.put("sourceRoot", version.packageManifest() == null
                    ? "" : version.packageManifest().path("sourceRoot").asText(""));
            snapshot.put("packageManifestJson", json(version.packageManifest()));
            snapshot.put("riskReportJson", json(version.riskReport()));
            snapshot.put("hasScripts", version.hasScripts());
            snapshot.put("activationMode", activationMode);
            snapshot.put("scriptPolicy", scriptPolicy);
            snapshot.put("required", Boolean.TRUE.equals(selection.required()));
            snapshot.put("enabled", selection.enabled() == null || selection.enabled());
            // Array order is the public contract. Canonicalizing to a dense sequence prevents
            // duplicate, negative, or sparse client priorities from creating ambiguous activation order.
            snapshot.put("priority", index);
            snapshots.add(snapshot);
            index++;
        }

        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("skills", List.copyOf(snapshots));
        return result;
    }

    private Selection selection(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AgentSkillException("SKILL_BINDING_INVALID", HttpStatus.BAD_REQUEST,
                    "Each Agent Skill binding must be an object");
        }
        Long skillId = positiveLong(map.get("skillId"), "skillId");
        Long versionId = positiveLong(map.get("skillVersionId"), "skillVersionId");
        return new Selection(
                skillId,
                versionId,
                text(map.get("activationMode")),
                text(map.get("scriptPolicy")),
                bool(map.get("required"), "required"),
                bool(map.get("enabled"), "enabled"));
    }

    private PlatformAuthenticatedSession requireSession(HttpServletRequest request) {
        Object candidate = request == null ? null
                : request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE);
        if (candidate instanceof PlatformAuthenticatedSession session) {
            return session;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "live ReachAI platform login is required");
    }

    private Long positiveLong(Object value, String field) {
        if (!(value instanceof Number number) || number.longValue() <= 0L) {
            throw new AgentSkillException("SKILL_BINDING_INVALID", HttpStatus.BAD_REQUEST,
                    field + " must be a positive number");
        }
        return number.longValue();
    }

    private Boolean bool(Object value, String field) {
        if (value == null) return null;
        if (!(value instanceof Boolean result)) {
            throw new AgentSkillException("SKILL_BINDING_INVALID", HttpStatus.BAD_REQUEST,
                    field + " must be a boolean");
        }
        return result;
    }

    private String text(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    private String upperOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AgentSkillException("SKILL_METADATA_SERIALIZATION_FAILED",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Skill binding metadata could not be serialized", exception);
        }
    }

    private record Selection(
            Long skillId,
            Long skillVersionId,
            String activationMode,
            String scriptPolicy,
            Boolean required,
            Boolean enabled) {
    }
}
