package com.enterprise.ai.runtime.a2a;

import com.enterprise.ai.runtime.client.control.RuntimeA2aControlClient;
import com.enterprise.ai.runtime.client.control.RuntimeA2aControlClient.SendRequest;
import com.enterprise.ai.runtime.client.control.RuntimeA2aControlClient.SendResponse;
import com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingQuery;
import com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Executes one immutable Runtime binding through the Control-owned A2A Hub. */
@Service
@RequiredArgsConstructor
public class RuntimeA2aDelegationService {

    private static final Pattern IDENTIFIER = Pattern.compile("[^\\r\\n]{1,128}");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final RuntimeAgentRemoteBindingQuery bindings;
    private final RuntimeA2aControlClient controlClient;
    private final ObjectMapper objectMapper;

    public SendResponse send(
            String agentId,
            long agentConfigVersionId,
            long bindingId,
            DelegationRequest request) {
        String normalizedAgentId = identifier(agentId, "agentId");
        RuntimeAgentRemoteBindingView binding = bindings.requireActiveBinding(
                normalizedAgentId, agentConfigVersionId, bindingId);
        DelegationRequest body = request == null ? new DelegationRequest(
                null, null, null, null, null, null, null, null, null) : request;
        String protocolSkillId = identifier(body.protocolSkillId(), "protocolSkillId");
        List<String> allowedSkills = strings(binding.getAllowedSkillIdsJson(), "allowedSkillIdsJson");
        if (!(allowedSkills.contains("*") || allowedSkills.contains(protocolSkillId))) {
            throw new IllegalArgumentException(
                    "protocolSkillId is outside the immutable A2A binding allowlist");
        }
        if (!supports(strings(binding.getInputModesJson(), "inputModesJson"), "text/plain")) {
            throw new IllegalStateException("A2A binding does not accept text/plain input");
        }
        List<String> outputModes = normalizeModes(body.acceptedOutputModes());
        List<String> declaredOutputs = strings(binding.getOutputModesJson(), "outputModesJson");
        for (String output : outputModes) {
            if (!supports(declaredOutputs, output)) {
                throw new IllegalArgumentException(
                        "acceptedOutputModes is outside the immutable A2A binding snapshot");
            }
        }
        long timeoutMs = binding.getTimeoutMs() == null ? 60_000L : binding.getTimeoutMs();
        if (timeoutMs <= 0) throw new IllegalStateException("A2A binding timeout is invalid");
        String messageId = StringUtils.hasText(body.messageId())
                ? identifier(body.messageId(), "messageId") : opaque("msg");
        return controlClient.send(new SendRequest(
                binding.getId(), normalizedAgentId, agentConfigVersionId,
                binding.getPrincipalId(), binding.getRemoteAgentId(),
                binding.getRemoteAgentRevisionId(), identifier(body.runtimeSessionId(), "runtimeSessionId"),
                optionalIdentifier(body.contextId(), "contextId"),
                optionalIdentifier(body.taskId(), "taskId"), messageId,
                requiredText(body.text(), 262_144, "text"), protocolSkillId,
                classification(body.contentClassification()), outputModes,
                body.historyLength(), timeoutMs,
                optionalIdentifier(body.traceId(), "traceId")), timeoutMs);
    }

    private List<String> strings(String json, String field) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : values.stream()
                    .filter(StringUtils::hasText).map(String::trim).distinct().toList();
        } catch (Exception failure) {
            throw new IllegalStateException("A2A binding " + field + " is invalid", failure);
        }
    }

    private List<String> normalizeModes(List<String> values) {
        if (values == null) return List.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private boolean supports(List<String> allowed, String requested) {
        String normalized = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        for (String candidate : allowed) {
            String value = candidate.toLowerCase(Locale.ROOT);
            if ("*/*".equals(value) || value.equals(normalized)) return true;
            int slash = value.indexOf('/');
            if (slash > 0 && value.endsWith("/*")
                    && normalized.startsWith(value.substring(0, slash + 1))) return true;
        }
        return false;
    }

    private String identifier(String value, String field) {
        if (!StringUtils.hasText(value) || !IDENTIFIER.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " is required and must be at most 128 characters");
        }
        return value.trim();
    }

    private String optionalIdentifier(String value, String field) {
        return StringUtils.hasText(value) ? identifier(value, field) : null;
    }

    private String requiredText(String value, int max, String field) {
        if (!StringUtils.hasText(value) || value.length() > max) {
            throw new IllegalArgumentException(field + " is required and exceeds its safety limit");
        }
        return value;
    }

    private String classification(String value) {
        String normalized = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT) : "INTERNAL";
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,31}")) {
            throw new IllegalArgumentException("contentClassification is invalid");
        }
        return normalized;
    }

    private String opaque(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record DelegationRequest(
            String runtimeSessionId,
            String contextId,
            String taskId,
            String messageId,
            String text,
            String protocolSkillId,
            String contentClassification,
            List<String> acceptedOutputModes,
            Integer historyLength,
            String traceId) {
        public DelegationRequest {
            acceptedOutputModes = acceptedOutputModes == null
                    ? List.of() : List.copyOf(acceptedOutputModes);
        }

        /** Compatibility constructor for tests and direct callers without trace id. */
        public DelegationRequest(
                String runtimeSessionId,
                String contextId,
                String taskId,
                String messageId,
                String text,
                String protocolSkillId,
                String contentClassification,
                List<String> acceptedOutputModes,
                Integer historyLength) {
            this(runtimeSessionId, contextId, taskId, messageId, text, protocolSkillId,
                    contentClassification, acceptedOutputModes, historyLength, null);
        }
    }
}
