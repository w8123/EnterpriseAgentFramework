package com.enterprise.ai.control.a2a.api.protocol;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Artifact;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.ListTasksResponse;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Message;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Part;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageConfiguration;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.SendMessageRequest;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.Task;
import static com.enterprise.ai.control.a2a.application.protocol.A2aProtocolModels.TaskStatus;
import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.PartProfile;
import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.SendCommand;
import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.TaskResource;

@Component
public class A2aHttpJsonMapper {

    private final ObjectMapper mapper;
    private final A2aHubProperties properties;

    public A2aHttpJsonMapper(ObjectMapper objectMapper, A2aHubProperties properties) {
        this.mapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.properties = properties;
    }

    public SendCommand toCommand(SendMessageRequest request) {
        if (request == null || request.message() == null) {
            throw invalid("message is required");
        }
        Message normalized = normalizeMessage(request.message());
        PartProfile partProfile = validateParts(normalized.parts());
        SendMessageConfiguration configuration = request.configuration();
        Integer historyLength = configuration == null ? null : configuration.historyLength();
        if (historyLength != null && historyLength < 0) {
            throw invalid("historyLength must not be negative");
        }
        List<String> acceptedModes = normalizeMediaTypes(
                configuration == null ? null : configuration.acceptedOutputModes());
        byte[] canonical = write(normalized);
        String classification = classification(normalized);
        return new SendCommand(
                trimToNull(request.tenant()), normalized.messageId(), normalized.contextId(),
                normalized.taskId(), canonical, sha256(canonical), canonical.length,
                safeSummary(normalized.parts()), "{}", classification, partProfile, acceptedModes,
                historyLength, configuration != null && Boolean.TRUE.equals(configuration.returnImmediately()),
                configuration != null && configuration.taskPushNotificationConfig() != null);
    }

    public Task toTask(TaskResource resource) {
        List<Message> history = resource.historyCanonicalJson().stream()
                .map(json -> read(json, Message.class, "Message"))
                .toList();
        List<Artifact> artifacts = resource.artifactCanonicalJson() == null ? null
                : resource.artifactCanonicalJson().stream()
                        .map(json -> read(json, Artifact.class, "Artifact"))
                        .toList();
        String timestamp = resource.statusTimestamp() == null ? null
                : resource.statusTimestamp().atOffset(ZoneOffset.UTC).toString();
        return new Task(resource.taskId(), resource.contextId(),
                new TaskStatus(resource.state().name(), null, timestamp),
                artifacts, history.isEmpty() ? null : history, null);
    }

    public ListTasksResponse toListResponse(
            List<TaskResource> resources, long total, int pageSize, int offset) {
        int nextOffset = offset + resources.size();
        String nextToken = nextOffset < total ? encodeOffset(nextOffset) : "";
        return new ListTasksResponse(resources.stream().map(this::toTask).toList(),
                nextToken, pageSize, total);
    }

    public int decodeOffset(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith("a2a-offset:")) {
                throw new IllegalArgumentException("wrong prefix");
            }
            int offset = Integer.parseInt(decoded.substring("a2a-offset:".length()));
            if (offset < 0) {
                throw new IllegalArgumentException("negative offset");
            }
            return offset;
        } catch (IllegalArgumentException exception) {
            throw invalid("pageToken is invalid");
        }
    }

    public int historyLength(Integer requested) {
        if (requested != null && requested < 0) {
            throw invalid("historyLength must not be negative");
        }
        int value = requested == null ? properties.getMaxHistoryMessages() : requested;
        return Math.max(0, Math.min(value, properties.getMaxHistoryMessages()));
    }

    private Message normalizeMessage(Message message) {
        String messageId = identifier(message.messageId(), "messageId", true);
        String contextId = identifier(message.contextId(), "contextId", false);
        String taskId = identifier(message.taskId(), "taskId", false);
        if (!"ROLE_USER".equals(message.role())) {
            throw invalid("inbound message role must be ROLE_USER");
        }
        if (message.parts() == null || message.parts().isEmpty() || message.parts().size() > 64) {
            throw invalid("message must contain between 1 and 64 Parts");
        }
        if (message.extensions() != null && !message.extensions().isEmpty()) {
            throw new A2aDomainException("A2A_UNSUPPORTED_OPERATION",
                    "message extensions are not enabled for this publication");
        }
        List<String> references = normalizeIdentifiers(message.referenceTaskIds(), "referenceTaskIds");
        return new Message(messageId, contextId, taskId, "ROLE_USER", message.parts(),
                message.metadata(), null, references.isEmpty() ? null : references);
    }

    private PartProfile validateParts(List<Part> parts) {
        boolean text = false;
        boolean raw = false;
        boolean url = false;
        boolean data = false;
        Set<String> mediaTypes = new LinkedHashSet<>();
        long decodedRawBytes = 0;
        for (Part part : parts) {
            if (part == null) {
                throw invalid("message Part must not be null");
            }
            int choices = present(part.text()) + present(part.raw()) + present(part.url())
                    + (part.data() == null ? 0 : 1);
            if (choices != 1) {
                throw invalid("each Part must contain exactly one of text, raw, url, or data");
            }
            validatePartMetadata(part);
            if (part.text() != null) {
                if (part.text().isEmpty()) {
                    throw invalid("text Part must not be empty");
                }
                text = true;
                mediaTypes.add(normalizeMediaType(part.mediaType(), "text/plain"));
            } else if (part.raw() != null) {
                raw = true;
                try {
                    decodedRawBytes += Base64.getDecoder().decode(part.raw()).length;
                } catch (IllegalArgumentException exception) {
                    throw invalid("raw Part must be valid Base64");
                }
                mediaTypes.add(normalizeMediaType(part.mediaType(), "application/octet-stream"));
            } else if (part.url() != null) {
                url = true;
                requireSafeUrl(part.url());
                mediaTypes.add(normalizeMediaType(part.mediaType(), "text/uri-list"));
            } else {
                data = true;
                mediaTypes.add(normalizeMediaType(part.mediaType(), "application/json"));
            }
        }
        if (decodedRawBytes > Integer.MAX_VALUE) {
            throw invalid("decoded raw Parts are too large");
        }
        return new PartProfile(text, raw, url, data, mediaTypes);
    }

    private void validatePartMetadata(Part part) {
        if (part.filename() != null) {
            String filename = part.filename().trim();
            if (filename.isEmpty() || filename.length() > 255
                    || filename.indexOf('\r') >= 0 || filename.indexOf('\n') >= 0) {
                throw invalid("Part filename is invalid");
            }
        }
    }

    private void requireSafeUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || value.length() > 2048) {
                throw new IllegalArgumentException("unsafe URL");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid("URL Part must use an absolute HTTPS URL without credentials");
        }
    }

    private String safeSummary(List<Part> parts) {
        long text = parts.stream().filter(part -> part != null && part.text() != null).count();
        long data = parts.stream().filter(part -> part != null && part.data() != null).count();
        long raw = parts.stream().filter(part -> part != null && part.raw() != null).count();
        long url = parts.stream().filter(part -> part != null && part.url() != null).count();
        // Never copy Message content into plaintext status, audit, or event projections.
        return "Message received: parts=" + parts.size()
                + ", text=" + text + ", data=" + data + ", raw=" + raw + ", url=" + url;
    }

    private String classification(Message message) {
        JsonNode value = message.metadata() == null ? null : message.metadata().get("dataClassification");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return "INTERNAL";
        }
        String normalized = value.asText().trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,31}")) {
            throw invalid("metadata.dataClassification is invalid");
        }
        return normalized;
    }

    private List<String> normalizeMediaTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(value -> normalizeMediaType(value, null)).distinct().toList();
    }

    private String normalizeMediaType(String value, String fallback) {
        String selected = value == null || value.isBlank() ? fallback : value.trim();
        if (selected == null || selected.length() > 128
                || !selected.matches("[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*\\-]+")) {
            throw invalid("mediaType is invalid");
        }
        return selected.toLowerCase(Locale.ROOT);
    }

    private List<String> normalizeIdentifiers(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > 64) {
            throw invalid(field + " contains too many values");
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            normalized.add(identifier(value, field, true));
        }
        return normalized.stream().distinct().toList();
    }

    private String identifier(String value, String field, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field + " is required");
            }
            return null;
        }
        if (!value.equals(value.trim()) || value.length() > 128
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private int present(String value) {
        return value == null ? 0 : 1;
    }

    private byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            A2aDomainException error = new A2aDomainException("A2A_MESSAGE_INVALID",
                    "message could not be serialized");
            error.initCause(exception);
            throw error;
        }
    }

    private <T> T read(String json, Class<T> type, String resource) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            A2aDomainException error = new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                    "persisted " + resource + " does not conform to the protocol model");
            error.initCause(exception);
            throw error;
        }
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("a2a-offset:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private A2aDomainException invalid(String detail) {
        return new A2aDomainException("A2A_INVALID_ARGUMENT", detail);
    }
}
