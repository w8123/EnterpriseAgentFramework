package com.enterprise.ai.control.aicoding.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AiCodingSensitiveJsonSanitizer {

    private static final Pattern REACHAI_EPHEMERAL_SECRET = Pattern.compile(
            "(?i)\\b(?:rtt|rhc)_[a-z0-9._~-]{8,}\\b");
    private static final Pattern BEARER_CREDENTIAL = Pattern.compile(
            "(?i)\\bBearer\\s+[a-z0-9._~+/-]{8,}=*");

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "accesskey",
            "aicodingkey",
            "registryappsecret",
            "appsecret",
            "password",
            "tasktoken",
            "activationcode",
            "authorization",
            "clientsecret",
            "privatekey",
            "secret",
            "secretkey",
            "apikey",
            "accesstoken",
            "refreshtoken",
            "bearertoken",
            "sessiontoken",
            "idtoken",
            "signingkey",
            "encryptionkey");

    public JsonNode sanitize(JsonNode source) {
        if (source == null || source.isNull()) {
            return source;
        }
        JsonNode copy = source.deepCopy();
        sanitizeInPlace(copy);
        return copy;
    }

    public String sanitizeText(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        String withoutReachAiSecrets = REACHAI_EPHEMERAL_SECRET
                .matcher(source)
                .replaceAll("[REDACTED]");
        return BEARER_CREDENTIAL
                .matcher(withoutReachAiSecrets)
                .replaceAll("Bearer [REDACTED]");
    }

    private void sanitizeInPlace(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (sensitive(field.getKey())) {
                    fields.remove();
                } else if (field.getValue().isTextual()) {
                    object.put(
                            field.getKey(),
                            sanitizeText(field.getValue().textValue()));
                } else {
                    sanitizeInPlace(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode item = array.get(index);
                if (item.isTextual()) {
                    array.set(
                            index,
                            com.fasterxml.jackson.databind.node.TextNode.valueOf(
                                    sanitizeText(item.textValue())));
                } else {
                    sanitizeInPlace(item);
                }
            }
        }
    }

    private static boolean sensitive(String key) {
        String normalized = key == null
                ? ""
                : key.replace("-", "")
                        .replace("_", "")
                        .toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized)
                || normalized.endsWith("secret")
                || normalized.endsWith("password")
                || normalized.endsWith("token")
                || normalized.endsWith("privatekey");
    }
}
