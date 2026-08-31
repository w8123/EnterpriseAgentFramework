package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.application.port.A2aRemoteCardParser;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCapabilities;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCardSnapshot;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteProtocolSkill;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class JacksonA2aRemoteCardParser implements A2aRemoteCardParser {

    private static final Pattern MEDIA_TYPE = Pattern.compile(
            "[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*\\-]+");
    private final ObjectMapper mapper;
    private final A2aOutboundTargetPolicy targetPolicy;

    public JacksonA2aRemoteCardParser(ObjectMapper objectMapper, A2aOutboundTargetPolicy targetPolicy) {
        this.mapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.targetPolicy = targetPolicy;
    }

    @Override
    public A2aRemoteCardSnapshot parse(byte[] body) {
        JsonNode root = read(body);
        if (!root.isObject()) {
            throw invalid("Agent Card root must be a JSON object");
        }
        String canonical = write(sort(root));
        List<A2aRemoteInterface> interfaces = interfaces(root.path("supportedInterfaces"));
        JsonNode capabilities = root.path("capabilities");
        if (!capabilities.isObject()) {
            throw invalid("capabilities is required and must be an object");
        }
        A2aRemoteCapabilities parsedCapabilities = new A2aRemoteCapabilities(
                bool(capabilities, "streaming"),
                bool(capabilities, "pushNotifications"),
                bool(capabilities, "extendedAgentCard"));
        List<String> inputModes = strings(root.path("defaultInputModes"),
                "defaultInputModes", 32, true, true);
        List<String> outputModes = strings(root.path("defaultOutputModes"),
                "defaultOutputModes", 32, true, true);
        List<A2aRemoteProtocolSkill> skills = skills(root.get("skills"));
        JsonNode provider = root.get("provider");
        if (provider != null && !provider.isNull() && !provider.isObject()) {
            throw invalid("provider must be an object");
        }
        String providerOrganization = provider != null && provider.isObject()
                ? requiredText(provider, "organization", 255) : null;
        String providerUrl = provider != null && provider.isObject()
                ? requiredText(provider, "url", 1000) : null;
        validateReferenceUrl(providerUrl, "provider.url");
        String documentationUrl = optionalText(root, "documentationUrl", 1000);
        String iconUrl = optionalText(root, "iconUrl", 1000);
        validateReferenceUrl(documentationUrl, "documentationUrl");
        validateReferenceUrl(iconUrl, "iconUrl");
        JsonNode schemes = root.get("securitySchemes");
        JsonNode requirements = root.get("securityRequirements");
        validateSecurity(schemes, requirements);
        String securitySchemesJson = schemes == null || schemes.isNull() || schemes.isEmpty()
                ? null : write(sort(schemes));
        String securityRequirementsJson = requirements == null || requirements.isNull()
                || requirements.isEmpty() ? null : write(sort(requirements));
        JsonNode signatures = root.get("signatures");
        if (signatures != null && !signatures.isNull() && !signatures.isArray()) {
            throw invalid("signatures must be an array");
        }
        String signingKeyId = validateSignatures(signatures);
        String signatureStatus = signatures == null || !signatures.isArray() || signatures.isEmpty()
                ? "NOT_PRESENT" : "UNTRUSTED_KEY";
        return new A2aRemoteCardSnapshot(
                requiredText(root, "name", 128),
                requiredText(root, "description", 2000),
                providerOrganization,
                providerUrl,
                documentationUrl,
                iconUrl,
                requiredText(root, "version", 64),
                interfaces,
                parsedCapabilities,
                inputModes,
                outputModes,
                skills,
                securitySchemesJson,
                securityRequirementsJson,
                canonical,
                sha256(canonical),
                signatureStatus,
                signingKeyId);
    }

    private JsonNode read(byte[] body) {
        if (body == null || body.length == 0) {
            throw invalid("Agent Card body is empty");
        }
        try {
            return mapper.readTree(body);
        } catch (Exception failure) {
            throw invalid("Agent Card is not valid duplicate-free JSON");
        }
    }

    private List<A2aRemoteInterface> interfaces(JsonNode value) {
        if (!value.isArray() || value.isEmpty() || value.size() > 20) {
            throw invalid("supportedInterfaces must contain between 1 and 20 entries");
        }
        List<A2aRemoteInterface> result = new ArrayList<>();
        var interfaceKeys = new HashSet<String>();
        for (JsonNode item : value) {
            if (!item.isObject()) throw invalid("supportedInterfaces entries must be objects");
            String url = requiredText(item, "url", 1000);
            String binding = requiredText(item, "protocolBinding", 64);
            String version = requiredText(item, "protocolVersion", 16);
            String tenant = optionalText(item, "tenant", 256);
            A2aOutboundTargetPolicy.ValidatedTarget target;
            try {
                target = targetPolicy.validate(URI.create(url));
            } catch (IllegalArgumentException invalidUri) {
                throw invalid("supported interface URL is invalid");
            }
            String normalizedUrl = target.uri().toASCIIString();
            String interfaceKey = sha256(
                    binding.toUpperCase(Locale.ROOT) + "\n" + version + "\n" + normalizedUrl)
                    .substring(0, 32);
            if (!interfaceKeys.add(interfaceKey)) {
                throw invalid("supportedInterfaces contains a duplicate interface");
            }
            result.add(new A2aRemoteInterface(
                    interfaceKey,
                    normalizedUrl,
                    binding,
                    version,
                    tenant));
        }
        return List.copyOf(result);
    }

    private List<A2aRemoteProtocolSkill> skills(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 100) {
            throw invalid("skills must be an array with at most 100 entries");
        }
        List<A2aRemoteProtocolSkill> result = new ArrayList<>();
        var skillIds = new HashSet<String>();
        for (JsonNode item : value) {
            if (!item.isObject()) throw invalid("skills entries must be objects");
            List<String> tags = strings(item.path("tags"), "skills.tags", 64, true, false);
            if (tags.isEmpty()) throw invalid("each skill requires at least one tag");
            String skillId = requiredText(item, "id", 128);
            if (!skillIds.add(skillId)) {
                throw invalid("skills contains a duplicate id");
            }
            result.add(new A2aRemoteProtocolSkill(
                    skillId,
                    requiredText(item, "name", 128),
                    requiredText(item, "description", 2000),
                    tags,
                    strings(item.path("examples"), "skills.examples", 32, false, false),
                    strings(item.path("inputModes"), "skills.inputModes", 32, false, true),
                    strings(item.path("outputModes"), "skills.outputModes", 32, false, true)));
        }
        return List.copyOf(result);
    }

    private List<String> strings(
            JsonNode value, String field, int maxItems, boolean required, boolean mediaTypes) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            if (required) throw invalid(field + " is required");
            return List.of();
        }
        if (!value.isArray() || value.size() > maxItems || (required && value.isEmpty())) {
            throw invalid(field + " must be a bounded non-empty string array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 512) {
                throw invalid(field + " contains an invalid string");
            }
            String normalized = item.textValue().trim();
            if (mediaTypes && !MEDIA_TYPE.matcher(normalized).matches()) {
                throw invalid(field + " contains an invalid media type");
            }
            if (!result.contains(normalized)) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private boolean bool(JsonNode parent, String field) {
        if (parent == null || !parent.isObject()) return false;
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw invalid("capabilities." + field + " must be boolean");
        return value.booleanValue();
    }

    private String requiredText(JsonNode parent, String field, int max) {
        String value = optionalText(parent, field, max);
        if (value == null) throw invalid(field + " is required");
        return value;
    }

    private String optionalText(JsonNode parent, String field, int max) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max) {
            throw invalid(field + " is invalid");
        }
        return value.textValue().trim();
    }

    private void validateReferenceUrl(String value, String field) {
        if (value == null) return;
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw invalid(field + " must be an absolute HTTPS URL");
            }
        } catch (IllegalArgumentException failure) {
            throw invalid(field + " must be an absolute HTTPS URL");
        }
    }

    private void validateSecurity(JsonNode schemes, JsonNode requirements) {
        if (schemes != null && !schemes.isNull()) {
            if (!schemes.isObject() || schemes.size() > 20) {
                throw invalid("securitySchemes must be an object with at most 20 entries");
            }
            schemes.fields().forEachRemaining(entry -> validateSecurityScheme(entry.getKey(), entry.getValue()));
        }
        if (requirements == null || requirements.isNull()) return;
        if (!requirements.isArray() || requirements.size() > 20) {
            throw invalid("securityRequirements must be an array with at most 20 entries");
        }
        for (JsonNode requirement : requirements) {
            if (!requirement.isObject() || requirement.size() > 10) {
                throw invalid("securityRequirements entries must be bounded objects");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = requirement.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (schemes == null || !schemes.isObject() || !schemes.has(entry.getKey())) {
                    throw invalid("securityRequirements references an unknown security scheme");
                }
                strings(entry.getValue(), "securityRequirements.scopes", 100, false, false);
            }
        }
    }

    private void validateSecurityScheme(String key, JsonNode value) {
        if (key == null || key.isBlank() || key.length() > 128 || value == null || !value.isObject()) {
            throw invalid("securitySchemes contains an invalid entry");
        }
        List<String> variants = List.of(
                "apiKeySecurityScheme", "httpAuthSecurityScheme", "oauth2SecurityScheme",
                "openIdConnectSecurityScheme", "mtlsSecurityScheme");
        List<String> present = variants.stream().filter(value::has).toList();
        if (present.size() != 1 || value.size() != 1 || !value.path(present.get(0)).isObject()) {
            throw invalid("each security scheme must contain exactly one supported variant");
        }
        JsonNode definition = value.path(present.get(0));
        switch (present.get(0)) {
            case "apiKeySecurityScheme" -> {
                String location = requiredText(definition, "location", 16);
                if (!List.of("header", "query", "cookie").contains(location.toLowerCase(Locale.ROOT))) {
                    throw invalid("API key security scheme location is invalid");
                }
                requiredText(definition, "name", 128);
            }
            case "httpAuthSecurityScheme" -> requiredText(definition, "scheme", 64);
            case "oauth2SecurityScheme" -> {
                if (!definition.path("flows").isObject()) {
                    throw invalid("OAuth2 security scheme flows is required");
                }
                validateReferenceUrl(optionalText(definition, "oauth2MetadataUrl", 1000),
                        "oauth2MetadataUrl");
            }
            case "openIdConnectSecurityScheme" -> validateReferenceUrl(
                    requiredText(definition, "openIdConnectUrl", 1000), "openIdConnectUrl");
            case "mtlsSecurityScheme" -> {
                // The variant is intentionally empty in the A2A schema.
            }
            default -> throw invalid("security scheme variant is unsupported");
        }
    }

    private String validateSignatures(JsonNode signatures) {
        if (signatures == null || signatures.isNull()) return null;
        if (!signatures.isArray() || signatures.size() > 10) {
            throw invalid("signatures must be an array with at most 10 entries");
        }
        String firstKeyId = null;
        for (JsonNode signature : signatures) {
            if (!signature.isObject()) throw invalid("signatures entries must be objects");
            String protectedHeader = requiredText(signature, "protected", 4096);
            String encodedSignature = requiredText(signature, "signature", 8192);
            if (!protectedHeader.matches("[A-Za-z0-9_-]+")
                    || !encodedSignature.matches("[A-Za-z0-9_-]+")) {
                throw invalid("Agent Card JWS values must use base64url encoding");
            }
            try {
                JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(protectedHeader));
                String algorithm = requiredText(header, "alg", 32);
                if ("none".equalsIgnoreCase(algorithm)) {
                    throw invalid("unsigned JWS algorithms are forbidden");
                }
                String keyId = optionalText(header, "kid", 255);
                if (firstKeyId == null) firstKeyId = keyId;
            } catch (A2aDomainException known) {
                throw known;
            } catch (Exception failure) {
                throw invalid("Agent Card JWS protected header is invalid");
            }
        }
        return firstKeyId;
    }

    private JsonNode sort(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            fields.forEachRemaining(entries::add);
            entries.stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> result.set(entry.getKey(), sort(entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(sort(item)));
            return result;
        }
        return value;
    }

    private String write(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw invalid("Agent Card could not be canonicalized");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private A2aDomainException invalid(String detail) {
        return new A2aDomainException("A2A_REMOTE_CARD_INVALID", detail);
    }
}
