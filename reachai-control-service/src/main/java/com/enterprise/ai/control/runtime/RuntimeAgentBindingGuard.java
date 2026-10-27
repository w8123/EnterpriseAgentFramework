package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteBindingSnapshotAssembler;
import com.enterprise.ai.control.agentskill.AgentSkillBindingSnapshotAssembler;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Attests Agent bindings before draft saves, publication and project migration. */
final class RuntimeAgentBindingGuard {

    private static final List<String> AGENT_SKILL_ATTESTED_FIELDS = List.of(
            "skillId", "skillVersionId", "publisher", "name", "visibility", "projectCode",
            "version", "sourceSha256", "contentTreeSha256", "sourceRoot",
            "packageManifestJson", "riskReportJson", "hasScripts", "activationMode",
            "scriptPolicy", "required", "enabled", "priority");
    private static final List<String> A2A_REMOTE_ATTESTED_FIELDS = List.of(
            "principalId", "remoteAgentId", "remoteAgentRevisionId", "remoteAgentKey",
            "toolName", "description", "allowedSkillIds", "inputModes", "outputModes",
            "riskLevel", "permissionKey", "timeoutMs", "enabled", "priority");

    private final RuntimeProxyClient runtimeProxyClient;
    private final AgentSkillBindingSnapshotAssembler agentSkillBindingSnapshotAssembler;
    private A2aRemoteBindingSnapshotAssembler a2aRemoteBindingSnapshotAssembler;

    RuntimeAgentBindingGuard(RuntimeProxyClient runtimeProxyClient,
                             AgentSkillBindingSnapshotAssembler agentSkillBindingSnapshotAssembler) {
        this.runtimeProxyClient = runtimeProxyClient;
        this.agentSkillBindingSnapshotAssembler = agentSkillBindingSnapshotAssembler;
    }

    void setA2aRemoteBindingSnapshotAssembler(A2aRemoteBindingSnapshotAssembler assembler) {
        this.a2aRemoteBindingSnapshotAssembler = assembler;
    }

    Map<String, Object> assembleDraft(HttpServletRequest request, String agentId, Map<String, Object> body) {
        Map<String, Object> runtimeBody;
        if (agentSkillBindingSnapshotAssembler == null) {
            runtimeBody = body;
        } else {
            String agentProjectCode = body != null && body.containsKey("skills")
                    ? resolveAgentProjectCode(agentId) : null;
            runtimeBody = agentSkillBindingSnapshotAssembler.assemble(request, body, agentProjectCode);
        }
        if (a2aRemoteBindingSnapshotAssembler != null) {
            runtimeBody = a2aRemoteBindingSnapshotAssembler.assemble(
                    request, runtimeBody, agentId);
        }
        return runtimeBody;
    }

    void requireProjectMigrationAllowed(String agentId, Map<String, Object> body) {
        if (agentSkillBindingSnapshotAssembler == null || body == null
                || (!body.containsKey("projectId") && !body.containsKey("projectCode"))) {
            return;
        }
        ResponseEntity<Object> currentResponse = runtimeProxyClient.getAgent(agentId);
        if (currentResponse == null || !currentResponse.getStatusCode().is2xxSuccessful()
                || !(currentResponse.getBody() instanceof Map<?, ?> current)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent project scope could not be checked before update");
        }
        boolean projectCodeChanged = body.containsKey("projectCode")
                && !Objects.equals(normalizedText(current.get("projectCode")),
                normalizedText(body.get("projectCode")));
        boolean projectIdChanged = body.containsKey("projectId")
                && !Objects.equals(normalizedLong(current.get("projectId")),
                normalizedLong(body.get("projectId")));
        boolean projectChanged = projectCodeChanged || projectIdChanged;
        if (!projectChanged) return;

        ResponseEntity<Object> configResponse = runtimeProxyClient.listAgentConfigVersions(agentId);
        if (configResponse == null || !configResponse.getStatusCode().is2xxSuccessful()
                || !(configResponse.getBody() instanceof Collection<?> versions)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent Skill scopes could not be checked before project migration");
        }
        if (hasProjectScopedSkill(versions)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Agent project cannot change while a saved config references a project-scoped Skill; "
                            + "remove an unpublished draft binding first, otherwise create a new Agent");
        }
    }

    private boolean hasProjectScopedSkill(Collection<?> versions) {
        for (Object version : versions) {
            if (!(version instanceof Map<?, ?> config)
                    || !(config.get("skills") instanceof Collection<?> skills)) {
                continue;
            }
            for (Object value : skills) {
                if (value instanceof Map<?, ?> skill
                        && "PROJECT".equalsIgnoreCase(normalizedText(skill.get("visibility")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String resolveAgentProjectCode(String agentId) {
        ResponseEntity<Object> response = runtimeProxyClient.getAgent(agentId);
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || !(response.getBody() instanceof Map<?, ?> agent)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent project scope could not be resolved before Skill binding");
        }
        Object value = agent.get("projectCode");
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    void revalidateForPublish(HttpServletRequest request,
                              String agentId,
                              Long configVersionId) {
        if (agentSkillBindingSnapshotAssembler == null
                && a2aRemoteBindingSnapshotAssembler == null) {
            return;
        }
        ResponseEntity<Object> response = runtimeProxyClient.listAgentConfigVersions(agentId);
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || !(response.getBody() instanceof Collection<?> versions)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent config could not be revalidated before publishing");
        }
        Map<String, Object> target = versions.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(candidate -> sameId(candidate.get("id"), configVersionId))
                .findFirst()
                .map(this::stringKeyMap)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Agent config version not found: " + configVersionId));
        if (agentSkillBindingSnapshotAssembler != null && target.containsKey("skills")) {
            Object rawSkills = target.get("skills");
            if (!(rawSkills instanceof Collection<?> skills)) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Agent config returned an invalid Skill binding snapshot");
            }
            if (!skills.isEmpty()) {
                Map<String, Object> canonical = agentSkillBindingSnapshotAssembler.assemble(
                        request, target, resolveAgentProjectCode(agentId));
                requireAttestedSkillSnapshots(skills, canonical.get("skills"));
            }
        }
        revalidateA2aRemoteBindingsForPublish(request, agentId, target);
    }

    private void revalidateA2aRemoteBindingsForPublish(
            HttpServletRequest request,
            String agentId,
            Map<String, Object> target) {
        if (a2aRemoteBindingSnapshotAssembler == null || !target.containsKey("remoteAgents")) return;
        Object persistedValue = target.get("remoteAgents");
        if (!(persistedValue instanceof Collection<?> persisted)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent config returned an invalid A2A remote binding snapshot");
        }
        // The server-owned empty catalog has no remote snapshots to attest.
        // Ordinary Workflow-only publication must not require unrelated A2A management.
        if (persisted.isEmpty()) return;
        Map<String, Object> canonical = a2aRemoteBindingSnapshotAssembler.assemble(
                request, target, agentId);
        requireAttestedSnapshots(
                persisted, canonical.get("remoteAgents"), A2A_REMOTE_ATTESTED_FIELDS,
                "Agent A2A remote binding snapshot differs from the Control catalog; save the draft again before publishing");
    }

    private void requireAttestedSkillSnapshots(Collection<?> persisted, Object canonicalValue) {
        requireAttestedSnapshots(persisted, canonicalValue, AGENT_SKILL_ATTESTED_FIELDS,
                "Agent Skill binding snapshot differs from the Control catalog; save the draft again before publishing");
    }

    private void requireAttestedSnapshots(
            Collection<?> persisted,
            Object canonicalValue,
            List<String> fields,
            String conflictMessage) {
        if (!(canonicalValue instanceof Collection<?> canonical) || persisted.size() != canonical.size()) {
            throw snapshotConflict(conflictMessage);
        }
        var persistedIterator = persisted.iterator();
        var canonicalIterator = canonical.iterator();
        while (persistedIterator.hasNext()) {
            Object persistedValue = persistedIterator.next();
            Object canonicalEntry = canonicalIterator.next();
            if (!(persistedValue instanceof Map<?, ?> persistedMap)
                    || !(canonicalEntry instanceof Map<?, ?> canonicalMap)) {
                throw snapshotConflict(conflictMessage);
            }
            for (String field : fields) {
                if (!sameSnapshotValue(persistedMap.get(field), canonicalMap.get(field))) {
                    throw snapshotConflict(conflictMessage);
                }
            }
        }
    }

    private boolean sameSnapshotValue(Object persisted, Object canonical) {
        if (persisted instanceof Number persistedNumber && canonical instanceof Number canonicalNumber) {
            return new java.math.BigDecimal(persistedNumber.toString())
                    .compareTo(new java.math.BigDecimal(canonicalNumber.toString())) == 0;
        }
        return Objects.equals(persisted, canonical);
    }

    private ResponseStatusException snapshotConflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private boolean sameId(Object value, Long expected) {
        if (expected == null) return false;
        if (value instanceof Number number) return number.longValue() == expected;
        try {
            return value != null && Long.parseLong(String.valueOf(value)) == expected;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String normalizedText(Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private Long normalizedLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null || !StringUtils.hasText(String.valueOf(value))
                    ? null : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
