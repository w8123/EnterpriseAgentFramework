package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredentialType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class JacksonA2aRemoteAuthenticationPlanner implements A2aRemoteAuthenticationPlanner {

    private static final Pattern HEADER_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "proxy-connection",
            "proxy-authorization", "proxy-authenticate", "upgrade", "trailer", "te", "cookie",
            "set-cookie", "a2a-version", "content-type", "accept", "user-agent", "forwarded");

    private final ObjectMapper objectMapper;

    @Override
    public Assessment assess(String schemesJson, String requirementsJson) {
        JsonNode requirements = read(requirementsJson, true, "securityRequirements");
        if (requirements == null || requirements.isEmpty()) {
            return new Assessment(false, true, List.of(), "AUTHENTICATION_NOT_REQUIRED");
        }
        if (!requirements.isArray()) {
            throw invalid("securityRequirements must be an array");
        }
        JsonNode schemes = read(schemesJson, false, "securitySchemes");
        if (schemes == null || !schemes.isObject()) {
            throw invalid("securitySchemes is required when authentication is declared");
        }

        boolean anonymousAllowed = false;
        Map<String, Option> options = new LinkedHashMap<>();
        for (JsonNode requirement : requirements) {
            if (!requirement.isObject()) {
                throw invalid("security requirement alternatives must be objects");
            }
            if (requirement.isEmpty()) {
                anonymousAllowed = true;
                continue;
            }
            boolean composite = requirement.size() != 1;
            Iterator<Map.Entry<String, JsonNode>> fields = requirement.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode declared = schemes.get(key);
                if (declared == null) {
                    throw invalid("security requirement references an unknown scheme");
                }
                List<String> scopes = scopes(field.getValue());
                Option parsed = parseOption(key, declared, scopes, composite);
                options.merge(key, parsed, this::preferSelectable);
            }
        }

        List<Option> values = List.copyOf(options.values());
        boolean supported = values.stream().anyMatch(Option::selectable);
        boolean required = !anonymousAllowed;
        String code;
        if (anonymousAllowed && supported) {
            code = "OPTIONAL_AUTHENTICATION_SUPPORTED";
        } else if (anonymousAllowed) {
            code = "ANONYMOUS_ACCESS_SUPPORTED";
        } else if (supported) {
            code = "SUPPORTED";
        } else {
            code = "NO_SUPPORTED_AUTHENTICATION_ALTERNATIVE";
        }
        return new Assessment(required, anonymousAllowed, values, code);
    }

    @Override
    public Binding requireBinding(
            String schemesJson,
            String requirementsJson,
            String requestedSecuritySchemeKey,
            A2aCredential credential,
            LocalDateTime now) {
        Assessment assessment = assess(schemesJson, requirementsJson);
        String requested = requestedSecuritySchemeKey == null
                ? null : requestedSecuritySchemeKey.trim();
        if (requested == null || requested.isEmpty()) {
            if (credential != null) {
                throw new A2aDomainException("A2A_REMOTE_AUTH_SCHEME_REQUIRED",
                        "a security scheme must be selected before binding a credential");
            }
            if (assessment.authenticationRequired()) {
                throw new A2aDomainException("A2A_REMOTE_AUTH_SCHEME_REQUIRED",
                        "the Agent Card requires a supported authentication scheme");
            }
            return Binding.anonymous();
        }

        Option option = assessment.options().stream()
                .filter(value -> value.securitySchemeKey().equals(requested))
                .findFirst()
                .orElseThrow(() -> new A2aDomainException("A2A_REMOTE_AUTH_SCHEME_NOT_ALLOWED",
                        "the selected security scheme is not an Agent Card requirement alternative"));
        if (!option.selectable()) {
            throw new A2aDomainException(option.supportCode(),
                    "the selected Agent Card security scheme is not supported by this release");
        }
        if (credential == null || credential.id() == null) {
            throw new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_REQUIRED",
                    "the selected authentication scheme requires an outbound credential");
        }
        if (credential.direction() != A2aDirection.OUTBOUND
                || !"ENCRYPTED".equals(credential.materialMode())) {
            throw new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_DIRECTION_INVALID",
                    "only encrypted OUTBOUND credentials can be bound to remote Agents");
        }
        if (now == null || !credential.usableAt(now)) {
            throw new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_NOT_USABLE",
                    "the outbound credential is inactive, not yet valid, or expired");
        }
        if (credential.credentialType() != option.credentialType()) {
            throw new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_TYPE_MISMATCH",
                    "the outbound credential type does not match the selected security scheme");
        }
        String prefix = option.credentialType() == A2aCredentialType.BEARER ? "Bearer " : "";
        return new Binding(option.securitySchemeKey(), credential.id(), option.credentialType(),
                option.headerName(), prefix, option.scopes());
    }

    private Option parseOption(
            String key, JsonNode wrapper, List<String> scopes, boolean composite) {
        if (!wrapper.isObject() || wrapper.size() != 1) {
            throw invalid("security scheme wrappers must contain exactly one variant");
        }
        if (composite) {
            return unsupported(key, variantName(wrapper), scopes,
                    "A2A_COMPOSITE_SECURITY_REQUIREMENT_UNSUPPORTED");
        }
        if (wrapper.has("apiKeySecurityScheme")) {
            JsonNode definition = wrapper.path("apiKeySecurityScheme");
            String location = text(definition, "location").toLowerCase(Locale.ROOT);
            String header = text(definition, "name");
            if (!"header".equals(location)) {
                return new Option(key, "API_KEY", A2aCredentialType.API_KEY, location,
                        null, null, scopes, false, "A2A_API_KEY_PLACEMENT_UNSUPPORTED");
            }
            if (!safeHeader(header)) {
                return new Option(key, "API_KEY", A2aCredentialType.API_KEY, location,
                        null, null, scopes, false, "A2A_API_KEY_HEADER_UNSAFE");
            }
            return new Option(key, "API_KEY", A2aCredentialType.API_KEY, "HEADER",
                    header, null, scopes, true, "SUPPORTED");
        }
        if (wrapper.has("httpAuthSecurityScheme")) {
            JsonNode definition = wrapper.path("httpAuthSecurityScheme");
            String scheme = text(definition, "scheme");
            if (!"bearer".equalsIgnoreCase(scheme)) {
                return new Option(key, "HTTP_AUTH", null, "HEADER", "Authorization",
                        scheme, scopes, false, "A2A_HTTP_AUTH_SCHEME_UNSUPPORTED");
            }
            return new Option(key, "HTTP_BEARER", A2aCredentialType.BEARER, "HEADER",
                    "Authorization", "Bearer", scopes, true, "SUPPORTED");
        }
        if (wrapper.has("oauth2SecurityScheme")) {
            return unsupported(key, "OAUTH2", scopes, "A2A_OAUTH2_NOT_IMPLEMENTED");
        }
        if (wrapper.has("openIdConnectSecurityScheme")) {
            return unsupported(key, "OPENID_CONNECT", scopes, "A2A_OPENID_CONNECT_NOT_IMPLEMENTED");
        }
        if (wrapper.has("mtlsSecurityScheme")) {
            return unsupported(key, "MTLS", scopes, "A2A_MTLS_NOT_IMPLEMENTED");
        }
        throw invalid("security scheme variant is unknown");
    }

    private Option unsupported(String key, String type, List<String> scopes, String code) {
        return new Option(key, type, null, null, null, null, scopes, false, code);
    }

    private Option preferSelectable(Option left, Option right) {
        if (left.selectable()) return left;
        return right.selectable() ? right : left;
    }

    private List<String> scopes(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw invalid("security requirement scopes must be arrays");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode scope : value) {
            if (!scope.isTextual() || scope.textValue().length() > 200) {
                throw invalid("security requirement scopes must be bounded strings");
            }
            result.add(scope.textValue());
        }
        return new ArrayList<>(result);
    }

    private String variantName(JsonNode wrapper) {
        Iterator<String> names = wrapper.fieldNames();
        return names.hasNext() ? names.next() : "UNKNOWN";
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("security scheme " + field + " is required");
        }
        return value.textValue();
    }

    private boolean safeHeader(String value) {
        if (value == null || !HEADER_TOKEN.matcher(value).matches()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return !FORBIDDEN_HEADERS.contains(lower)
                && !lower.startsWith("proxy-")
                && !lower.startsWith("sec-")
                && !lower.startsWith("x-forwarded-")
                && !"x-real-ip".equals(lower);
    }

    private JsonNode read(String json, boolean emptyArrayWhenBlank, String field) {
        if (json == null || json.isBlank()) {
            return emptyArrayWhenBlank ? objectMapper.createArrayNode() : null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception failure) {
            A2aDomainException invalid = invalid(field + " is invalid JSON");
            invalid.initCause(failure);
            throw invalid;
        }
    }

    private A2aDomainException invalid(String detail) {
        return new A2aDomainException("A2A_REMOTE_AUTH_METADATA_INVALID",
                "remote Agent authentication metadata is invalid: " + detail);
    }
}
