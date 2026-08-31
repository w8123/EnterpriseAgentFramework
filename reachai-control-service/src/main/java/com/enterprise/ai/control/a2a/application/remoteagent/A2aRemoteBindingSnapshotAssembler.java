package com.enterprise.ai.control.a2a.application.remoteagent;

import com.enterprise.ai.control.a2a.api.management.A2aHubManagementAccess;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentHealth;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Replaces browser-supplied remote metadata with Control catalog snapshots. */
@Component
@RequiredArgsConstructor
public class A2aRemoteBindingSnapshotAssembler {

    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,127}");
    private static final Set<String> RISKS = Set.of("READ", "WRITE", "IRREVERSIBLE");

    private final A2aHubManagementAccess access;
    private final A2aPrincipalRepository principals;
    private final A2aRemoteAgentRepository remoteAgents;
    private final A2aTrustProfileRepository trustProfiles;

    public Map<String, Object> assemble(
            HttpServletRequest request,
            Map<String, Object> draft,
            String runtimeAgentId) {
        Map<String, Object> source = draft == null ? Map.of() : draft;
        if (!source.containsKey("remoteAgents")) return source;
        access.require(request, A2aHubManagementAccess.MANAGE_REMOTE_AGENTS);
        if (!StringUtils.hasText(runtimeAgentId)) {
            throw invalid("runtime Agent identity is required before A2A binding");
        }
        Object raw = source.get("remoteAgents");
        if (raw != null && !(raw instanceof List<?>)) {
            throw invalid("Agent config remoteAgents must be an array");
        }
        List<?> selections = raw == null ? List.of() : (List<?>) raw;
        if (selections.size() > 32) {
            throw invalid("an Agent config cannot bind more than 32 remote Agents");
        }

        List<Map<String, Object>> snapshots = new ArrayList<>(selections.size());
        Set<Long> revisionIds = new LinkedHashSet<>();
        Set<String> toolNames = new LinkedHashSet<>();
        int index = 0;
        for (Object value : selections) {
            Selection selection = selection(value);
            A2aPrincipal principal = principals.findById(selection.principalId())
                    .filter(item -> item.status() == A2aPrincipalStatus.ACTIVE)
                    .orElseThrow(() -> invalid("LOCAL_AGENT Principal is not active"));
            if (principal.principalType() != A2aPrincipalType.LOCAL_AGENT
                    || !runtimeAgentId.equals(principal.attributes().get("runtimeAgentId"))) {
                throw invalid("LOCAL_AGENT Principal is not bound to this Runtime Agent");
            }
            A2aRemoteAgent remote = remoteAgents.findById(selection.remoteAgentId())
                    .orElseThrow(() -> invalid("remote Agent was not found"));
            if (remote.status() != A2aRemoteAgentStatus.TRUSTED
                    || remote.healthStatus() == A2aRemoteAgentHealth.UNREACHABLE
                    || remote.currentRevisionId() == null
                    || !remote.currentRevisionId().equals(selection.remoteAgentRevisionId())) {
                throw invalid("remote Agent is not callable at the selected fixed revision");
            }
            A2aRemoteAgentRevision revision = remoteAgents.findRevision(
                            remote.id(), selection.remoteAgentRevisionId())
                    .filter(item -> item.reviewStatus() == A2aRemoteRevisionReviewStatus.APPROVED)
                    .orElseThrow(() -> invalid("remote Agent revision is not approved"));
            revision.requireInterface(remote.preferredInterfaceKey());
            A2aTrustProfile principalTrust = activeTrust(principal.trustProfileId());
            A2aTrustProfile remoteTrust = activeTrust(remote.trustProfileId());
            if (!principalTrust.direction().accepts(A2aDirection.OUTBOUND)
                    || !remoteTrust.direction().accepts(A2aDirection.OUTBOUND)
                    || !scope(principal.tenantScope()).equals(scope(remote.tenantScope()))) {
                throw invalid("Principal and remote Agent outbound trust or tenant scope does not match");
            }
            requireAllowed(principalTrust.authorizationPolicy().operations(), "message:send");
            requireAllowed(remoteTrust.authorizationPolicy().operations(), "message:send");
            requireAllowed(principalTrust.authorizationPolicy().operations(), "tasks:get");
            requireAllowed(remoteTrust.authorizationPolicy().operations(), "tasks:get");
            requireAllowed(principalTrust.authorizationPolicy().operations(), "tasks:cancel");
            requireAllowed(remoteTrust.authorizationPolicy().operations(), "tasks:cancel");
            List<String> allowedSkills = selection.allowedSkillIds();
            Set<String> declaredSkills = revision.card().protocolSkills().stream()
                    .map(skill -> skill.id()).collect(java.util.stream.Collectors.toSet());
            if (allowedSkills.isEmpty() || !declaredSkills.containsAll(allowedSkills)) {
                throw invalid("allowedSkillIds must be a non-empty subset of the fixed Agent Card");
            }
            for (String skillId : allowedSkills) {
                requireAllowed(principalTrust.authorizationPolicy().protocolSkillIds(), skillId);
                requireAllowed(remoteTrust.authorizationPolicy().protocolSkillIds(), skillId);
            }
            requireAllowed(principalTrust.authorizationPolicy().remoteAgentKeys(),
                    remote.remoteAgentKey());
            requireAllowed(remoteTrust.authorizationPolicy().remoteAgentKeys(),
                    remote.remoteAgentKey());
            if (!revisionIds.add(revision.id())) {
                throw invalid("duplicate remote Agent revision binding");
            }
            if (!toolNames.add(selection.toolName())) {
                throw invalid("duplicate remote Agent toolName");
            }
            long effectiveTimeout = Math.min(principalTrust.taskTimeoutMs(), remoteTrust.taskTimeoutMs());
            long timeout = selection.timeoutMs() == null ? effectiveTimeout : selection.timeoutMs();
            if (timeout < 1_000L || timeout > Math.min(effectiveTimeout, 600_000L)) {
                throw invalid("timeoutMs exceeds the effective Trust Profile limit");
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("principalId", principal.id());
            snapshot.put("remoteAgentId", remote.id());
            snapshot.put("remoteAgentRevisionId", revision.id());
            snapshot.put("remoteAgentKey", remote.remoteAgentKey());
            snapshot.put("toolName", selection.toolName());
            snapshot.put("description", revision.card().name() + ": " + revision.card().description());
            snapshot.put("allowedSkillIds", allowedSkills);
            snapshot.put("inputModes", revision.card().defaultInputModes());
            snapshot.put("outputModes", revision.card().defaultOutputModes());
            snapshot.put("riskLevel", selection.riskLevel());
            snapshot.put("permissionKey", selection.permissionKey());
            snapshot.put("timeoutMs", timeout);
            snapshot.put("enabled", selection.enabled());
            snapshot.put("priority", index++);
            snapshots.add(snapshot);
        }
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("remoteAgents", List.copyOf(snapshots));
        return result;
    }

    private Selection selection(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid("each remote Agent binding must be an object");
        }
        String toolName = text(map.get("toolName"));
        if (toolName == null || !TOOL_NAME.matcher(toolName).matches()) {
            throw invalid("remote Agent toolName is invalid");
        }
        String risk = upper(text(map.get("riskLevel")), "READ");
        if (!RISKS.contains(risk)) throw invalid("riskLevel must be READ, WRITE, or IRREVERSIBLE");
        String permissionKey = text(map.get("permissionKey"));
        if (permissionKey == null || permissionKey.length() > 160) {
            throw invalid("permissionKey is required and must not exceed 160 characters");
        }
        List<String> skillIds = stringList(map.get("allowedSkillIds"), "allowedSkillIds", 64, 200);
        return new Selection(
                positiveLong(map.get("principalId"), "principalId"),
                positiveLong(map.get("remoteAgentId"), "remoteAgentId"),
                positiveLong(map.get("remoteAgentRevisionId"), "remoteAgentRevisionId"),
                toolName, skillIds, risk, permissionKey,
                optionalPositiveLong(map.get("timeoutMs"), "timeoutMs"),
                bool(map.get("enabled"), true));
    }

    private A2aTrustProfile activeTrust(Long id) {
        if (id == null) throw invalid("Trust Profile reference is missing");
        return trustProfiles.findActiveById(id)
                .orElseThrow(() -> invalid("Trust Profile is not active"));
    }

    private void requireAllowed(Set<String> values, String requested) {
        if (values == null || !(values.contains("*") || values.contains(requested))) {
            throw invalid("binding selection is outside the effective Trust Profile allowlist");
        }
    }

    private List<String> stringList(Object value, String field, int maxItems, int maxLength) {
        if (!(value instanceof List<?> list) || list.size() > maxItems) {
            throw invalid(field + " must be a bounded array");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : list) {
            String text = text(item);
            if (text == null || text.length() > maxLength) throw invalid(field + " contains an invalid value");
            values.add(text);
        }
        return List.copyOf(values);
    }

    private Long positiveLong(Object value, String field) {
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw invalid(field + " must be a positive number");
        }
        return number.longValue();
    }

    private Long optionalPositiveLong(Object value, String field) {
        return value == null ? null : positiveLong(value, field);
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw invalid("enabled must be boolean");
        return result;
    }

    private String text(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    private String upper(String value, String fallback) {
        return value == null ? fallback : value.toUpperCase(Locale.ROOT);
    }

    private String scope(String value) {
        return value == null ? "" : value.trim();
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Selection(
            long principalId,
            long remoteAgentId,
            long remoteAgentRevisionId,
            String toolName,
            List<String> allowedSkillIds,
            String riskLevel,
            String permissionKey,
            Long timeoutMs,
            boolean enabled) {
    }
}
