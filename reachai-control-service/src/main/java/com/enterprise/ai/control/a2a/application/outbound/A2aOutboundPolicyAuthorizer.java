package com.enterprise.ai.control.a2a.application.outbound;

import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationContracts.SendRequest;

/** Computes the strict intersection of local-Agent and remote-Agent outbound policies. */
@Service
public class A2aOutboundPolicyAuthorizer {

    public EffectivePolicy authorize(
            SendRequest request,
            A2aPrincipal principal,
            A2aTrustProfile principalTrust,
            A2aRemoteAgent remoteAgent,
            A2aRemoteAgentRevision revision,
            A2aRemoteInterface remoteInterface,
            A2aTrustProfile remoteTrust,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            long encodedMessageBytes,
            LocalDateTime now) {
        requireOutbound(principalTrust, "LOCAL_AGENT");
        requireOutbound(remoteTrust, "remote Agent");
        requireAllowed(principalTrust.authorizationPolicy().remoteAgentKeys(),
                remoteAgent.remoteAgentKey(), "A2A_REMOTE_AGENT_FORBIDDEN");
        requireAllowed(remoteTrust.authorizationPolicy().remoteAgentKeys(),
                remoteAgent.remoteAgentKey(), "A2A_REMOTE_AGENT_FORBIDDEN");
        requireAllowed(principalTrust.authorizationPolicy().operations(),
                "message:send", "A2A_OUTBOUND_OPERATION_FORBIDDEN");
        requireAllowed(remoteTrust.authorizationPolicy().operations(),
                "message:send", "A2A_OUTBOUND_OPERATION_FORBIDDEN");

        String tenant = scope(principal.tenantScope());
        if (!tenant.equals(scope(remoteAgent.tenantScope()))) {
            throw forbidden("A2A_OUTBOUND_TENANT_MISMATCH",
                    "the LOCAL_AGENT Principal and remote Agent must share a tenant scope");
        }
        requireTenant(principalTrust, tenant);
        requireTenant(remoteTrust, tenant);

        String skillId = text(request.protocolSkillId());
        if (skillId == null) {
            throw new A2aDomainException("A2A_OUTBOUND_SKILL_REQUIRED",
                    "protocolSkillId is required for governed outbound delegation");
        }
        if (revision.card().protocolSkills().stream().noneMatch(skill -> skill.id().equals(skillId))) {
            throw forbidden("A2A_OUTBOUND_SKILL_NOT_DECLARED",
                    "the selected protocol AgentSkill is not declared by the fixed remote revision");
        }
        requireAllowed(principalTrust.authorizationPolicy().protocolSkillIds(), skillId,
                "A2A_OUTBOUND_SKILL_FORBIDDEN");
        requireAllowed(remoteTrust.authorizationPolicy().protocolSkillIds(), skillId,
                "A2A_OUTBOUND_SKILL_FORBIDDEN");

        A2aAuthenticationMethod method = authentication.authenticated()
                ? A2aAuthenticationMethod.valueOf(authentication.credentialType().name())
                : A2aAuthenticationMethod.ANONYMOUS;
        if (!principalTrust.authenticationMethods().contains(method)
                || !remoteTrust.authenticationMethods().contains(method)) {
            throw forbidden("A2A_OUTBOUND_AUTH_FORBIDDEN",
                    "the reviewed authentication method is outside the effective Trust Profile intersection");
        }
        requireScopes(principal.scopes(), authentication.scopes());
        requireScopes(principalTrust.allowedScopes(), authentication.scopes());
        requireScopes(remoteTrust.allowedScopes(), authentication.scopes());

        String classification = classification(request.contentClassification());
        requireClassification(principalTrust, classification);
        requireClassification(remoteTrust, classification);
        if (!principalTrust.dataPolicy().allowTextParts()
                || !remoteTrust.dataPolicy().allowTextParts()) {
            throw forbidden("A2A_OUTBOUND_TEXT_FORBIDDEN",
                    "text Parts are outside the effective data policy");
        }
        long maxRequestBytes = Math.min(principalTrust.maxRequestBytes(), remoteTrust.maxRequestBytes());
        if (encodedMessageBytes <= 0 || encodedMessageBytes > maxRequestBytes) {
            throw new A2aDomainException("A2A_OUTBOUND_REQUEST_TOO_LARGE",
                    "the outbound Message exceeds the effective request size limit");
        }
        if (!supports(revision.card().defaultInputModes(), "text/plain")) {
            throw new A2aDomainException("A2A_OUTBOUND_INPUT_MODE_UNSUPPORTED",
                    "the fixed remote revision does not accept text/plain input");
        }

        List<String> requestedOutputs = normalizeModes(request.acceptedOutputModes());
        List<String> effectiveOutputs = requestedOutputs.isEmpty()
                ? revision.card().defaultOutputModes() : requestedOutputs;
        for (String output : effectiveOutputs) {
            if (!supports(revision.card().defaultOutputModes(), output)) {
                throw new A2aDomainException("A2A_OUTBOUND_OUTPUT_MODE_UNSUPPORTED",
                        "the fixed remote revision does not declare output mode " + output);
            }
        }

        long governedTimeout = Math.min(principalTrust.taskTimeoutMs(), remoteTrust.taskTimeoutMs());
        if (request.timeoutMs() != null) {
            if (request.timeoutMs() <= 0) {
                throw new A2aDomainException("A2A_OUTBOUND_TIMEOUT_INVALID",
                        "binding timeoutMs must be positive");
            }
            governedTimeout = Math.min(governedTimeout, request.timeoutMs());
        }
        int retentionDays = Math.min(principalTrust.dataPolicy().payloadRetentionDays(),
                remoteTrust.dataPolicy().payloadRetentionDays());
        LocalDateTime retention = retentionDays == 0
                ? now.plus(Duration.ofMillis(governedTimeout)) : now.plusDays(retentionDays);
        long maxArtifact = Math.min(principalTrust.maxArtifactBytes(), remoteTrust.maxArtifactBytes());
        int maxResponseBytes = (int) Math.max(1024L, Math.min(maxArtifact, 32L * 1024 * 1024));
        int maxConcurrentTasks = Math.min(principalTrust.maxConcurrentTasks(),
                remoteTrust.maxConcurrentTasks());
        return new EffectivePolicy(
                tenant, classification, List.copyOf(effectiveOutputs), skillId,
                governedTimeout, retention, maxRequestBytes, maxResponseBytes,
                maxArtifact, maxConcurrentTasks);
    }

    public EffectivePolicy authorizeTaskOperation(
            String operation,
            A2aPrincipal principal,
            A2aTrustProfile principalTrust,
            A2aRemoteAgent remoteAgent,
            A2aRemoteAgentRevision revision,
            A2aRemoteInterface remoteInterface,
            A2aTrustProfile remoteTrust,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            AcceptedPolicy accepted,
            LocalDateTime now) {
        if (accepted == null) {
            throw new A2aDomainException("A2A_OUTBOUND_POLICY_SNAPSHOT_MISSING",
                    "the outbound Task has no immutable acceptance policy snapshot");
        }
        String normalizedOperation = operation == null ? "" : operation.trim();
        if (!("tasks:get".equals(normalizedOperation) || "tasks:cancel".equals(normalizedOperation))) {
            throw new A2aDomainException("A2A_OUTBOUND_OPERATION_INVALID",
                    "the outbound Task operation is invalid");
        }
        if (remoteInterface == null || !remoteInterface.supportedByFirstRelease()) {
            throw new A2aDomainException("A2A_REMOTE_INTERFACE_NOT_SUPPORTED",
                    "the fixed Task interface is not A2A 1.0 HTTP+JSON");
        }
        requireOutbound(principalTrust, "LOCAL_AGENT");
        requireOutbound(remoteTrust, "remote Agent");
        requireAllowed(principalTrust.authorizationPolicy().remoteAgentKeys(),
                remoteAgent.remoteAgentKey(), "A2A_REMOTE_AGENT_FORBIDDEN");
        requireAllowed(remoteTrust.authorizationPolicy().remoteAgentKeys(),
                remoteAgent.remoteAgentKey(), "A2A_REMOTE_AGENT_FORBIDDEN");
        requireAllowed(principalTrust.authorizationPolicy().operations(),
                normalizedOperation, "A2A_OUTBOUND_OPERATION_FORBIDDEN");
        requireAllowed(remoteTrust.authorizationPolicy().operations(),
                normalizedOperation, "A2A_OUTBOUND_OPERATION_FORBIDDEN");

        String tenant = scope(principal.tenantScope());
        if (!tenant.equals(scope(remoteAgent.tenantScope()))) {
            throw forbidden("A2A_OUTBOUND_TENANT_MISMATCH",
                    "the LOCAL_AGENT Principal and remote Agent must share a tenant scope");
        }
        requireTenant(principalTrust, tenant);
        requireTenant(remoteTrust, tenant);
        String skillId = text(accepted.protocolSkillId());
        if (skillId == null
                || revision.card().protocolSkills().stream().noneMatch(skill -> skill.id().equals(skillId))) {
            throw forbidden("A2A_OUTBOUND_SKILL_NOT_DECLARED",
                    "the accepted protocol AgentSkill is not declared by the fixed remote revision");
        }
        requireAllowed(principalTrust.authorizationPolicy().protocolSkillIds(), skillId,
                "A2A_OUTBOUND_SKILL_FORBIDDEN");
        requireAllowed(remoteTrust.authorizationPolicy().protocolSkillIds(), skillId,
                "A2A_OUTBOUND_SKILL_FORBIDDEN");

        A2aAuthenticationMethod method = authentication.authenticated()
                ? A2aAuthenticationMethod.valueOf(authentication.credentialType().name())
                : A2aAuthenticationMethod.ANONYMOUS;
        if (!principalTrust.authenticationMethods().contains(method)
                || !remoteTrust.authenticationMethods().contains(method)) {
            throw forbidden("A2A_OUTBOUND_AUTH_FORBIDDEN",
                    "the fixed authentication method is outside the current Trust Profile intersection");
        }
        requireScopes(principal.scopes(), authentication.scopes());
        requireScopes(principalTrust.allowedScopes(), authentication.scopes());
        requireScopes(remoteTrust.allowedScopes(), authentication.scopes());

        String classification = classification(accepted.contentClassification());
        requireClassification(principalTrust, classification);
        requireClassification(remoteTrust, classification);
        if (!principalTrust.dataPolicy().allowTextParts()
                || !remoteTrust.dataPolicy().allowTextParts()) {
            throw forbidden("A2A_OUTBOUND_TEXT_FORBIDDEN",
                    "text Parts are outside the current data policy");
        }
        List<String> outputs = normalizeModes(accepted.acceptedOutputModes());
        if (outputs.isEmpty()) {
            throw new A2aDomainException("A2A_OUTBOUND_POLICY_SNAPSHOT_INVALID",
                    "the accepted output-mode snapshot is empty");
        }
        for (String output : outputs) {
            if (!supports(revision.card().defaultOutputModes(), output)) {
                throw new A2aDomainException("A2A_OUTBOUND_OUTPUT_MODE_UNSUPPORTED",
                        "the fixed revision no longer matches the accepted output-mode snapshot");
            }
        }

        long currentTimeout = Math.min(principalTrust.taskTimeoutMs(), remoteTrust.taskTimeoutMs());
        long governedTimeout = Math.min(currentTimeout, accepted.timeoutMs());
        long currentMaxRequest = Math.min(principalTrust.maxRequestBytes(), remoteTrust.maxRequestBytes());
        long maxRequest = Math.min(currentMaxRequest, accepted.maxRequestBytes());
        long currentMaxArtifact = Math.min(principalTrust.maxArtifactBytes(), remoteTrust.maxArtifactBytes());
        long maxArtifact = Math.min(currentMaxArtifact, accepted.maxArtifactBytes());
        int currentMaxResponse = (int) Math.max(1024L,
                Math.min(currentMaxArtifact, 32L * 1024 * 1024));
        int maxResponse = Math.min(currentMaxResponse, accepted.maxResponseBytes());
        int retentionDays = Math.min(principalTrust.dataPolicy().payloadRetentionDays(),
                remoteTrust.dataPolicy().payloadRetentionDays());
        LocalDateTime retention = retentionDays == 0
                ? now.plus(Duration.ofMillis(governedTimeout)) : now.plusDays(retentionDays);
        return new EffectivePolicy(
                tenant, classification, outputs, skillId, governedTimeout, retention,
                maxRequest, maxResponse, maxArtifact,
                Math.min(principalTrust.maxConcurrentTasks(), remoteTrust.maxConcurrentTasks()));
    }

    private void requireOutbound(A2aTrustProfile trust, String subject) {
        if (trust == null || !trust.direction().accepts(A2aDirection.OUTBOUND)) {
            throw forbidden("A2A_OUTBOUND_TRUST_DIRECTION_INVALID",
                    subject + " Trust Profile does not allow OUTBOUND operations");
        }
    }

    private void requireTenant(A2aTrustProfile trust, String tenant) {
        if (!tenant.isEmpty()) {
            requireAllowed(trust.authorizationPolicy().tenantScopes(), tenant,
                    "A2A_OUTBOUND_TENANT_FORBIDDEN");
        }
    }

    private void requireClassification(A2aTrustProfile trust, String classification) {
        requireAllowed(trust.dataPolicy().allowedDataClassifications(), classification,
                "A2A_OUTBOUND_CLASSIFICATION_FORBIDDEN");
    }

    private void requireScopes(Set<String> allowed, List<String> required) {
        if (required == null || required.isEmpty()) return;
        if (allowed == null || (!allowed.contains("*") && !allowed.containsAll(required))) {
            throw forbidden("A2A_OUTBOUND_SCOPE_FORBIDDEN",
                    "the remote authentication scope is outside the effective Principal scope");
        }
    }

    private void requireAllowed(Set<String> allowed, String value, String code) {
        if (allowed == null || !(allowed.contains("*") || allowed.contains(value))) {
            throw forbidden(code, "the requested outbound resource is outside the explicit allowlist");
        }
    }

    private boolean supports(List<String> allowed, String requested) {
        String normalized = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        for (String candidate : allowed == null ? List.<String>of() : allowed) {
            String value = candidate == null ? "" : candidate.trim().toLowerCase(Locale.ROOT);
            if ("*/*".equals(value) || value.equals(normalized)) return true;
            int slash = value.indexOf('/');
            if (slash > 0 && value.endsWith("/*")
                    && normalized.startsWith(value.substring(0, slash + 1))) return true;
        }
        return false;
    }

    private List<String> normalizeModes(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    private String classification(String value) {
        return value == null || value.isBlank() ? "INTERNAL" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String scope(String value) {
        return value == null ? "" : value.trim();
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private A2aDomainException forbidden(String code, String detail) {
        return new A2aDomainException(code, detail);
    }

    public record EffectivePolicy(
            String tenantScope,
            String classification,
            List<String> acceptedOutputModes,
            String protocolSkillId,
            long timeoutMs,
            LocalDateTime retentionExpiresAt,
            long maxRequestBytes,
            int maxResponseBytes,
            long maxArtifactBytes,
            int maxConcurrentTasks) {
    }

    public record AcceptedPolicy(
            String protocolSkillId,
            String contentClassification,
            List<String> acceptedOutputModes,
            long timeoutMs,
            long maxRequestBytes,
            int maxResponseBytes,
            long maxArtifactBytes) {
        public AcceptedPolicy {
            acceptedOutputModes = acceptedOutputModes == null
                    ? List.of() : List.copyOf(acceptedOutputModes);
        }
    }
}
