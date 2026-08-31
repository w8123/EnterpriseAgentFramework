package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.application.port.A2aAgentCardGenerator;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.publication.A2aAgentCardSnapshot;
import com.enterprise.ai.control.a2a.domain.publication.A2aProtocolSkill;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeterministicA2aAgentCardGenerator implements A2aAgentCardGenerator {

    private final ObjectMapper canonicalMapper;

    public DeterministicA2aAgentCardGenerator(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    @Override
    public A2aAgentCardSnapshot generate(Source source, A2aTrustProfile trustProfile) {
        requireSource(source);
        SecurityContract security = security(trustProfile);
        List<Skill> skills = source.protocolSkills().stream()
                .map(skill -> new Skill(skill.id(), skill.name(), skill.description(), skill.tags(),
                        skill.examples(), skill.inputModes(), skill.outputModes()))
                .toList();
        AgentCard card = new AgentCard(
                source.name().trim(),
                source.description().trim(),
                List.of(new AgentInterface(
                        source.publicOrigin() + source.protocolBasePath(),
                        "HTTP+JSON",
                        "1.0")),
                provider(source),
                source.agentVersion().trim(),
                trimToNull(source.documentationUrl()),
                new Capabilities(source.streamingSupported(), source.pushNotificationsSupported(),
                        source.extendedCardSupported()),
                security.schemes(),
                security.requirements(),
                List.copyOf(source.defaultInputModes()),
                List.copyOf(source.defaultOutputModes()),
                skills,
                trimToNull(source.iconUrl()));
        String cardJson = write(card, "Agent Card");
        String skillsJson = write(skills, "protocol skills");
        String inputModesJson = write(source.defaultInputModes(), "default input modes");
        String outputModesJson = write(source.defaultOutputModes(), "default output modes");
        String securitySchemesJson = security.schemes().isEmpty()
                ? null : write(security.schemes(), "security schemes");
        String securityRequirementsJson = security.requirements().isEmpty()
                ? null : write(security.requirements(), "security requirements");
        String validation = write(new ValidationSummary(
                "reachai.a2a-hub.agent-card-validation.v1",
                true,
                List.of(
                        "A2A_1_0_REQUIRED_FIELDS",
                        "HTTP_JSON_INTERFACE_DECLARED",
                        "CAPABILITIES_MATCH_IMPLEMENTATION",
                        "TRUST_PROFILE_SECURITY_PROJECTED",
                        "NO_CREDENTIAL_MATERIAL")), "validation summary");
        return new A2aAgentCardSnapshot(cardJson, sha256(cardJson), securitySchemesJson,
                securityRequirementsJson, skillsJson, inputModesJson, outputModesJson, validation);
    }

    private void requireSource(Source source) {
        if (source == null) {
            throw new A2aDomainException("A2A_AGENT_CARD_SOURCE_REQUIRED",
                    "Agent Card source is required");
        }
        if (source.streamingSupported() || source.pushNotificationsSupported()
                || source.extendedCardSupported()) {
            throw new A2aDomainException("A2A_CAPABILITY_NOT_IMPLEMENTED",
                    "streaming, push notifications, and extended Agent Card are not implemented yet");
        }
        requireAbsoluteUrl(source.publicOrigin(), "publicOrigin");
        requireOptionalUrl(source.providerUrl(), "providerUrl");
        requireOptionalUrl(source.documentationUrl(), "documentationUrl");
        requireOptionalUrl(source.iconUrl(), "iconUrl");
    }

    private Provider provider(Source source) {
        String organization = trimToNull(source.providerOrganization());
        String url = trimToNull(source.providerUrl());
        return organization == null ? null : new Provider(url, organization);
    }

    private SecurityContract security(A2aTrustProfile trustProfile) {
        if (trustProfile == null) {
            throw new A2aDomainException("A2A_TRUST_PROFILE_REQUIRED", "Trust Profile is required");
        }
        if (trustProfile.allowAnonymous()) {
            return new SecurityContract(Map.of(), List.of());
        }
        LinkedHashMap<String, SecurityScheme> schemes = new LinkedHashMap<>();
        List<SecurityRequirement> requirements = new ArrayList<>();
        trustProfile.authenticationMethods().stream()
                .sorted(Comparator.comparing(Enum::name))
                .forEach(method -> addSecurity(method, trustProfile, schemes, requirements));
        if (schemes.isEmpty()) {
            throw new A2aDomainException("A2A_ADVERTISABLE_AUTH_REQUIRED",
                    "no authentication method can be represented by the current HTTP+JSON adapter");
        }
        return new SecurityContract(Map.copyOf(schemes), List.copyOf(requirements));
    }

    private void addSecurity(
            A2aAuthenticationMethod method,
            A2aTrustProfile trustProfile,
            Map<String, SecurityScheme> schemes,
            List<SecurityRequirement> requirements) {
        String schemeName;
        SecurityScheme scheme;
        switch (method) {
            case API_KEY -> {
                schemeName = "reachaiApiKey";
                scheme = new SecurityScheme(
                        new ApiKeySecurityScheme(
                                "ReachAI-issued A2A API key; the value is provided out of band",
                                "header",
                                "X-ReachAI-A2A-Key"));
            }
            case HMAC, OAUTH2_CLIENT_CREDENTIALS, MTLS -> throw new A2aDomainException(
                    "A2A_AUTH_METHOD_NOT_ADVERTISABLE",
                    method.name() + " is not fully implemented by the inbound HTTP+JSON adapter");
            case ANONYMOUS -> throw new A2aDomainException("A2A_ANONYMOUS_CONTRACT_INVALID",
                    "anonymous authentication must be the only Trust Profile method");
            default -> throw new A2aDomainException("A2A_AUTH_METHOD_UNSUPPORTED",
                    "authentication method is not supported by the HTTP+JSON adapter");
        }
        schemes.put(schemeName, scheme);
        requirements.add(new SecurityRequirement(Map.of(
                schemeName,
                new ScopeList(trustProfile.allowedScopes().stream().sorted().toList()))));
    }

    private void requireOptionalUrl(String value, String field) {
        if (value != null && !value.isBlank()) {
            requireAbsoluteUrl(value, field);
        }
    }

    private void requireAbsoluteUrl(String value, String field) {
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw invalidUrl(field);
            }
        } catch (URISyntaxException exception) {
            throw invalidUrl(field);
        }
    }

    private A2aDomainException invalidUrl(String field) {
        return new A2aDomainException("A2A_URL_INVALID", field + " must be an absolute HTTP(S) URL");
    }

    private String write(Object value, String subject) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new A2aDomainException("A2A_AGENT_CARD_SERIALIZATION_FAILED",
                    subject + " could not be serialized");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private record AgentCard(
            String name,
            String description,
            List<AgentInterface> supportedInterfaces,
            Provider provider,
            String version,
            String documentationUrl,
            Capabilities capabilities,
            Map<String, SecurityScheme> securitySchemes,
            List<SecurityRequirement> securityRequirements,
            List<String> defaultInputModes,
            List<String> defaultOutputModes,
            List<Skill> skills,
            String iconUrl) {
    }

    private record AgentInterface(String url, String protocolBinding, String protocolVersion) {
    }

    private record Provider(String url, String organization) {
    }

    private record Capabilities(boolean streaming, boolean pushNotifications, boolean extendedAgentCard) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private record Skill(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples,
            List<String> inputModes,
            List<String> outputModes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SecurityScheme(
            ApiKeySecurityScheme apiKeySecurityScheme) {
    }

    private record ApiKeySecurityScheme(String description, String location, String name) {
    }

    private record SecurityRequirement(Map<String, ScopeList> schemes) {
    }

    private record ScopeList(List<String> list) {
    }

    private record SecurityContract(
            Map<String, SecurityScheme> schemes,
            List<SecurityRequirement> requirements) {
    }

    private record ValidationSummary(String schema, boolean valid, List<String> passedChecks) {
    }
}
