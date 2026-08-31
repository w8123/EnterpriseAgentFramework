package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Artifact;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.CancelTaskRequest;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Message;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Part;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageConfiguration;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageRequest;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageResponse;
import com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Task;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class JacksonA2aOutboundProtocolCodec implements A2aOutboundProtocolCodec {

    private static final Pattern MEDIA_TYPE = Pattern.compile(
            "[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*\\-]+");
    private final ObjectMapper mapper;

    public JacksonA2aOutboundProtocolCodec(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Override
    public EncodedRequest encodeTextMessage(
            String tenant,
            String messageId,
            String remoteContextId,
            String remoteTaskId,
            String text,
            String protocolSkillId,
            String contentClassification,
            List<String> acceptedOutputModes,
            int historyLength) {
        requireId(messageId, "messageId");
        optionalId(remoteContextId, "contextId");
        optionalId(remoteTaskId, "taskId");
        if (text == null || text.isBlank() || text.length() > 262_144) {
            throw invalid("outbound text must contain between 1 and 262144 characters");
        }
        if (historyLength < 0 || historyLength > 100) {
            throw invalid("historyLength must be between 0 and 100");
        }
        String skillId = requireOptionalProtocolSkillId(protocolSkillId);
        String classification = requireClassification(contentClassification);
        List<String> outputModes = mediaTypes(acceptedOutputModes);
        Map<String, JsonNode> metadata = new LinkedHashMap<>();
        metadata.put("reachai.a2a.contentClassification",
                mapper.getNodeFactory().textNode(classification));
        if (skillId != null) {
            metadata.put("reachai.a2a.protocolSkillId", mapper.getNodeFactory().textNode(skillId));
        }
        Message message = new Message(
                messageId, trim(remoteContextId), trim(remoteTaskId), "ROLE_USER",
                List.of(new Part(text, null, null, null, null, null, "text/plain")),
                metadata, null, null);
        SendMessageRequest request = new SendMessageRequest(
                trim(tenant), message,
                new SendMessageConfiguration(outputModes.isEmpty() ? null : outputModes,
                        null, historyLength, false),
                metadata);
        byte[] canonicalMessage = writeCanonical(mapper.valueToTree(message));
        byte[] body = writeCanonical(mapper.valueToTree(request));
        ObjectNode idempotency = mapper.createObjectNode();
        idempotency.put("messageId", messageId);
        idempotency.put("text", text);
        idempotency.put("protocolSkillId", skillId);
        idempotency.put("contentClassification", classification);
        idempotency.set("acceptedOutputModes", mapper.valueToTree(outputModes));
        idempotency.put("historyLength", historyLength);
        return new EncodedRequest(body, canonicalMessage, sha256(canonicalMessage),
                sha256(writeCanonical(idempotency)),
                canonicalMessage.length, "Outbound Message: parts=1, text=1");
    }

    @Override
    public DecodedResponse decodeSendResponse(byte[] responseBody) {
        JsonNode root = read(responseBody);
        if (!root.isObject()) throw invalidResponse("response root must be an object");
        boolean hasTask = root.hasNonNull("task");
        boolean hasMessage = root.hasNonNull("message");
        if (hasTask == hasMessage) {
            throw invalidResponse("SendMessageResponse must contain exactly one of task or message");
        }
        SendMessageResponse response = convert(root, SendMessageResponse.class);
        List<DecodedMessage> messages = new ArrayList<>();
        List<DecodedArtifact> artifacts = new ArrayList<>();
        String remoteTaskId;
        String remoteContextId;
        A2aTaskState state;
        LocalDateTime timestamp;
        if (response.task() != null) {
            DecodedTask decodedTask = decodeTask(response.task());
            remoteTaskId = decodedTask.remoteTaskId();
            remoteContextId = decodedTask.remoteContextId();
            state = decodedTask.state();
            timestamp = decodedTask.timestamp();
            messages.addAll(decodedTask.messages());
            artifacts.addAll(decodedTask.artifacts());
        } else {
            Message direct = response.message();
            DecodedMessage decoded = decodeMessage(direct);
            if (!"ROLE_AGENT".equals(decoded.role())) {
                throw invalidResponse("direct response message role must be ROLE_AGENT");
            }
            messages.add(decoded);
            optionalId(direct.taskId(), "message.taskId");
            optionalId(direct.contextId(), "message.contextId");
            remoteTaskId = trim(direct.taskId());
            remoteContextId = trim(direct.contextId());
            state = A2aTaskState.TASK_STATE_COMPLETED;
            timestamp = null;
        }
        messages = deduplicate(messages);
        String summary = "Remote response: state=" + state.name()
                + ", messages=" + messages.size() + ", artifacts=" + artifacts.size();
        return new DecodedResponse(
                new String(writeCanonical(sort(root)), StandardCharsets.UTF_8),
                remoteTaskId, remoteContextId, state, timestamp, summary, messages, artifacts);
    }

    @Override
    public EncodedOperation encodeCancelTask(String tenant, String remoteTaskId) {
        String taskId = requireRequestId(remoteTaskId, "taskId");
        CancelTaskRequest request = new CancelTaskRequest(trim(tenant), taskId, Map.of());
        byte[] body = writeCanonical(mapper.valueToTree(request));
        return new EncodedOperation(body, sha256(body));
    }

    @Override
    public DecodedResponse decodeTaskResponse(byte[] responseBody) {
        JsonNode root = read(responseBody);
        if (!root.isObject() || root.has("task") || root.has("message")) {
            throw invalidResponse("Task operation response must be one Task object");
        }
        DecodedTask task = decodeTask(convert(root, Task.class));
        String summary = "Remote response: state=" + task.state().name()
                + ", messages=" + task.messages().size()
                + ", artifacts=" + task.artifacts().size();
        return new DecodedResponse(
                new String(writeCanonical(sort(root)), StandardCharsets.UTF_8),
                task.remoteTaskId(), task.remoteContextId(), task.state(), task.timestamp(),
                summary, task.messages(), task.artifacts());
    }

    private DecodedTask decodeTask(Task task) {
        if (task == null) throw invalidResponse("task is required");
        String remoteTaskId = requireId(task.id(), "task.id");
        String remoteContextId = requireId(task.contextId(), "task.contextId");
        if (task.status() == null) throw invalidResponse("task.status is required");
        A2aTaskState state;
        try {
            state = A2aTaskState.parse(task.status().state());
        } catch (A2aDomainException failure) {
            throw invalidResponse("task.status.state is invalid");
        }
        if (!state.persistable()) throw invalidResponse("task state is not persistable");
        LocalDateTime timestamp = timestamp(task.status().timestamp());
        List<DecodedMessage> messages = new ArrayList<>();
        List<DecodedArtifact> artifacts = new ArrayList<>();
        addMessage(messages, task.status().message());
        if (task.history() != null) {
            if (task.history().size() > 100) throw invalidResponse("task history is too large");
            for (Message message : task.history()) addMessage(messages, message);
        }
        if (task.artifacts() != null) {
            if (task.artifacts().size() > 64) throw invalidResponse("task artifacts are too large");
            for (Artifact artifact : task.artifacts()) artifacts.add(decodeArtifact(artifact));
        }
        return new DecodedTask(remoteTaskId, remoteContextId, state, timestamp,
                deduplicate(messages), List.copyOf(artifacts));
    }

    private void addMessage(List<DecodedMessage> output, Message message) {
        if (message != null) output.add(decodeMessage(message));
    }

    private DecodedMessage decodeMessage(Message message) {
        if (message == null) throw invalidResponse("message is required");
        String id = requireId(message.messageId(), "message.messageId");
        String role = message.role();
        if (!"ROLE_USER".equals(role) && !"ROLE_AGENT".equals(role)) {
            throw invalidResponse("message.role is invalid");
        }
        PartSummary parts = parts(message.parts(), "message.parts");
        byte[] canonical = writeCanonical(mapper.valueToTree(message));
        return new DecodedMessage(id, role, canonical, sha256(canonical), canonical.length,
                parts.mediaTypes(), "Remote Message: role=" + role + ", " + parts.safeSummary());
    }

    private DecodedArtifact decodeArtifact(Artifact artifact) {
        if (artifact == null) throw invalidResponse("artifact is required");
        String id = requireId(artifact.artifactId(), "artifact.artifactId");
        PartSummary parts = parts(artifact.parts(), "artifact.parts");
        byte[] canonical = writeCanonical(mapper.valueToTree(artifact));
        String mediaTypesJson;
        try {
            mediaTypesJson = mapper.writeValueAsString(parts.mediaTypes());
        } catch (Exception failure) {
            throw invalidResponse("artifact media types could not be serialized");
        }
        return new DecodedArtifact(id, bounded(artifact.name(), 255),
                bounded(artifact.description(), 2000), canonical, sha256(canonical),
                canonical.length, mediaTypesJson, parts.mediaTypes(),
                "Remote Artifact: " + parts.safeSummary());
    }

    private PartSummary parts(List<Part> values, String field) {
        if (values == null || values.isEmpty() || values.size() > 64) {
            throw invalidResponse(field + " must contain between 1 and 64 Parts");
        }
        int text = 0;
        int raw = 0;
        int url = 0;
        int data = 0;
        Set<String> mediaTypes = new LinkedHashSet<>();
        for (Part part : values) {
            if (part == null) throw invalidResponse(field + " contains null");
            int choices = present(part.text()) + present(part.raw()) + present(part.url())
                    + (part.data() == null ? 0 : 1);
            if (choices != 1) throw invalidResponse("each response Part must contain one payload");
            if (part.text() != null) {
                if (part.text().length() > 4_000_000) throw invalidResponse("text Part is too large");
                text++;
                mediaTypes.add(mediaType(part.mediaType(), "text/plain"));
            } else if (part.raw() != null) {
                if (part.raw().length() > 44_000_000) throw invalidResponse("raw Part is too large");
                try {
                    Base64.getDecoder().decode(part.raw());
                } catch (IllegalArgumentException failure) {
                    throw invalidResponse("raw Part is not valid base64");
                }
                raw++;
                mediaTypes.add(mediaType(part.mediaType(), "application/octet-stream"));
            } else if (part.url() != null) {
                if (part.url().length() > 2048) throw invalidResponse("URL Part is too long");
                requireSafeResourceUrl(part.url());
                url++;
                mediaTypes.add(mediaType(part.mediaType(), "text/uri-list"));
            } else {
                data++;
                mediaTypes.add(mediaType(part.mediaType(), "application/json"));
            }
        }
        return new PartSummary(text, raw, url, data, List.copyOf(mediaTypes));
    }

    private List<DecodedMessage> deduplicate(List<DecodedMessage> values) {
        Map<String, DecodedMessage> unique = new LinkedHashMap<>();
        for (DecodedMessage value : values) {
            DecodedMessage previous = unique.putIfAbsent(value.messageId(), value);
            if (previous != null && !previous.payloadSha256().equals(value.payloadSha256())) {
                throw invalidResponse("the same response messageId has conflicting content");
            }
        }
        return List.copyOf(unique.values());
    }

    private JsonNode read(byte[] body) {
        if (body == null || body.length == 0) throw invalidResponse("response body is empty");
        try {
            return mapper.readTree(body);
        } catch (Exception failure) {
            throw invalidResponse("response is not duplicate-free JSON");
        }
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        try {
            return mapper.treeToValue(value, type);
        } catch (Exception failure) {
            throw invalidResponse("response does not conform to the A2A data model");
        }
    }

    private byte[] writeCanonical(JsonNode value) {
        try {
            return mapper.writeValueAsBytes(sort(value));
        } catch (Exception failure) {
            throw invalidResponse("protocol resource could not be serialized");
        }
    }

    private JsonNode sort(JsonNode value) {
        if (value == null || value.isValueNode()) return value;
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(sort(item)));
            return result;
        }
        ObjectNode result = mapper.createObjectNode();
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        value.fields().forEachRemaining(fields::add);
        fields.sort(Comparator.comparing(Map.Entry::getKey));
        fields.forEach(entry -> result.set(entry.getKey(), sort(entry.getValue())));
        return result;
    }

    private List<String> mediaTypes(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 32) throw invalid("acceptedOutputModes contains too many values");
        return values.stream().map(this::requestMediaType).distinct().toList();
    }

    private String requestMediaType(String value) {
        String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.length() > 128
                || !MEDIA_TYPE.matcher(normalized).matches()) {
            throw invalid("acceptedOutputModes contains an invalid media type");
        }
        return normalized;
    }

    private String mediaType(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback
                : value.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.length() > 128
                || !MEDIA_TYPE.matcher(normalized).matches()) {
            throw invalidResponse("media type is invalid");
        }
        return normalized;
    }

    private void requireSafeResourceUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                throw invalidResponse("URL Part must be an absolute HTTPS URL without credentials or fragment");
            }
        } catch (IllegalArgumentException failure) {
            throw invalidResponse("URL Part is invalid");
        }
    }

    private String requireId(String value, String field) {
        String normalized = trim(value);
        if (normalized == null || normalized.length() > 128
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw invalidResponse(field + " is invalid");
        }
        return normalized;
    }

    private String requireRequestId(String value, String field) {
        String normalized = trim(value);
        if (normalized == null || normalized.length() > 128
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw invalid(field + " is invalid");
        }
        return normalized;
    }

    private void optionalId(String value, String field) {
        if (value != null && !value.isBlank()) requireId(value, field);
    }

    private String requireOptionalProtocolSkillId(String value) {
        String normalized = trim(value);
        if (normalized == null) return null;
        if (normalized.length() > 200 || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw invalid("protocolSkillId is invalid");
        }
        return normalized;
    }

    private String requireClassification(String value) {
        String normalized = value == null || value.isBlank()
                ? "INTERNAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,31}")) {
            throw invalid("contentClassification is invalid");
        }
        return normalized;
    }

    private LocalDateTime timestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception failure) {
            throw invalidResponse("task status timestamp is invalid");
        }
    }

    private int present(String value) {
        return value == null ? 0 : 1;
    }

    private String bounded(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw invalidResponse("response text field is too long");
        return normalized.isEmpty() ? null : normalized;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private A2aDomainException invalid(String detail) {
        return new A2aDomainException("A2A_INVALID_ARGUMENT", detail);
    }

    private A2aDomainException invalidResponse(String detail) {
        return new A2aDomainException("A2A_INVALID_AGENT_RESPONSE", detail);
    }

    private record PartSummary(
            int text,
            int raw,
            int url,
            int data,
            List<String> mediaTypes) {
        String safeSummary() {
            return "parts=" + (text + raw + url + data) + ", text=" + text
                    + ", data=" + data + ", raw=" + raw + ", url=" + url;
        }
    }

    private record DecodedTask(
            String remoteTaskId,
            String remoteContextId,
            A2aTaskState state,
            LocalDateTime timestamp,
            List<DecodedMessage> messages,
            List<DecodedArtifact> artifacts) {
    }
}
