package com.enterprise.ai.control.aicoding.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deterministic validator for the JSON Schema subset used by AI Coding
 * artifact contracts.
 *
 * <p>The artifact schema is a ReachAI-owned protocol contract, not
 * AI-generated input. Keeping validation in the Task Kernel makes every
 * task-kind Provider receive the same strict envelope before it can mutate
 * domain state.</p>
 */
@Component
public class AiCodingArtifactContractValidator {

    private static final int MAX_SCHEMA_DEPTH = 128;

    private final AiCodingContractResourceLoader resourceLoader;
    private final Map<String, JsonNode> referencedSchemas =
            new ConcurrentHashMap<>();

    public AiCodingArtifactContractValidator(
            AiCodingContractResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public void requireValid(JsonNode instance, JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException(
                    "AI Coding artifact contract schema is missing or invalid");
        }
        validate(instance, schema, schema, "$", 0);
    }

    private void validate(
            JsonNode instance,
            JsonNode schema,
            JsonNode rootSchema,
            String path,
            int depth) {
        if (depth > MAX_SCHEMA_DEPTH) {
            throw new IllegalStateException(
                    "AI Coding artifact contract exceeds the reference depth limit");
        }
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException(
                    "AI Coding artifact contract contains an invalid schema at " + path);
        }

        JsonNode reference = schema.get("$ref");
        if (reference != null) {
            if (!reference.isTextual() || reference.asText().isBlank()) {
                throw new IllegalStateException(
                        "AI Coding artifact contract contains an invalid $ref at " + path);
            }
            ResolvedSchema resolved = resolveReference(
                    reference.asText(),
                    rootSchema);
            validate(
                    instance,
                    resolved.schema(),
                    resolved.rootSchema(),
                    path,
                    depth + 1);
        }

        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null) {
            requireArrayKeyword(anyOf, "anyOf", path);
            List<String> failures = new ArrayList<>();
            for (JsonNode option : anyOf) {
                try {
                    validate(instance, option, rootSchema, path, depth + 1);
                    failures.clear();
                    break;
                } catch (IllegalArgumentException ex) {
                    failures.add(ex.getMessage());
                }
            }
            if (!failures.isEmpty() || anyOf.isEmpty()) {
                throw invalid(path, "does not match any allowed schema");
            }
        }

        JsonNode type = schema.get("type");
        if (type != null && !matchesType(instance, type, path)) {
            throw invalid(path, "has an invalid JSON type; expected " + type);
        }

        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(instance)) {
            throw invalid(path, "must equal " + constant);
        }

        JsonNode allowedValues = schema.get("enum");
        if (allowedValues != null) {
            requireArrayKeyword(allowedValues, "enum", path);
            boolean matched = false;
            for (JsonNode allowed : allowedValues) {
                if (allowed.equals(instance)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw invalid(path, "must be one of " + allowedValues);
            }
        }

        if (instance == null || instance.isNull()) {
            return;
        }
        if (instance.isObject()) {
            validateObject(instance, schema, rootSchema, path, depth);
        } else if (instance.isArray()) {
            validateArray(instance, schema, rootSchema, path, depth);
        } else if (instance.isTextual()) {
            validateString(instance.asText(), schema, path);
        } else if (instance.isNumber()) {
            validateNumber(instance, schema, path);
        }
    }

    private void validateObject(
            JsonNode instance,
            JsonNode schema,
            JsonNode rootSchema,
            String path,
            int depth) {
        JsonNode required = schema.get("required");
        if (required != null) {
            requireArrayKeyword(required, "required", path);
            for (JsonNode field : required) {
                if (!field.isTextual()) {
                    throw new IllegalStateException(
                            "AI Coding artifact contract contains a non-string required field at "
                                    + path);
                }
                if (!instance.has(field.asText())) {
                    throw invalid(
                            childPath(path, field.asText()),
                            "is required");
                }
            }
        }

        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            throw new IllegalStateException(
                    "AI Coding artifact contract contains invalid properties at " + path);
        }
        if (properties != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (instance.has(field.getKey())) {
                    validate(
                            instance.get(field.getKey()),
                            field.getValue(),
                            rootSchema,
                            childPath(path, field.getKey()),
                            depth + 1);
                }
            }
        }

        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null
                && additionalProperties.isBoolean()
                && !additionalProperties.booleanValue()) {
            Iterator<String> fieldNames = instance.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (properties == null || !properties.has(fieldName)) {
                    throw invalid(
                            childPath(path, fieldName),
                            "is not allowed by the artifact contract");
                }
            }
        }
    }

    private void validateArray(
            JsonNode instance,
            JsonNode schema,
            JsonNode rootSchema,
            String path,
            int depth) {
        requireMinimum(instance.size(), schema.get("minItems"), "items", path);
        requireMaximum(instance.size(), schema.get("maxItems"), "items", path);
        JsonNode uniqueItems = schema.get("uniqueItems");
        if (uniqueItems != null && !uniqueItems.isBoolean()) {
            throw new IllegalStateException(
                    "AI Coding artifact contract contains invalid uniqueItems at " + path);
        }
        if (uniqueItems != null && uniqueItems.booleanValue()) {
            Set<JsonNode> seen = new HashSet<>();
            for (int index = 0; index < instance.size(); index++) {
                if (!seen.add(instance.get(index))) {
                    throw invalid(path + "[" + index + "]", "must be unique within the array");
                }
            }
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            for (int index = 0; index < instance.size(); index++) {
                validate(
                        instance.get(index),
                        items,
                        rootSchema,
                        path + "[" + index + "]",
                        depth + 1);
            }
        }
    }

    private void validateString(
            String value,
            JsonNode schema,
            String path) {
        requireMinimum(
                value.codePointCount(0, value.length()),
                schema.get("minLength"),
                "characters",
                path);
        requireMaximum(
                value.codePointCount(0, value.length()),
                schema.get("maxLength"),
                "characters",
                path);
        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            if (!pattern.isTextual()) {
                throw new IllegalStateException(
                        "AI Coding artifact contract contains invalid pattern at " + path);
            }
            try {
                if (!Pattern.compile(pattern.asText()).matcher(value).find()) {
                    throw invalid(path, "must match pattern " + pattern.asText());
                }
            } catch (PatternSyntaxException ex) {
                throw new IllegalStateException(
                        "AI Coding artifact contract contains invalid pattern at " + path, ex);
            }
        }
        JsonNode format = schema.get("format");
        if (format != null
                && format.isTextual()
                && "date-time".equals(format.asText())) {
            try {
                // The current page-map payload stores LocalDateTime. Accepting
                // ISO local or offset date-time keeps the wire contract usable
                // while still rejecting ambiguous free-form timestamps.
                DateTimeFormatter.ISO_DATE_TIME.parse(value);
            } catch (DateTimeParseException ex) {
                throw invalid(path, "must be an ISO-8601 date-time");
            }
        }
    }

    private void validateNumber(
            JsonNode instance,
            JsonNode schema,
            String path) {
        JsonNode minimum = schema.get("minimum");
        if (minimum != null
                && instance.decimalValue().compareTo(minimum.decimalValue()) < 0) {
            throw invalid(path, "must be at least " + minimum);
        }
        JsonNode maximum = schema.get("maximum");
        if (maximum != null
                && instance.decimalValue().compareTo(maximum.decimalValue()) > 0) {
            throw invalid(path, "must be at most " + maximum);
        }
    }

    private boolean matchesType(
            JsonNode instance,
            JsonNode type,
            String path) {
        if (type.isTextual()) {
            return matchesSingleType(instance, type.asText(), path);
        }
        if (type.isArray()) {
            for (JsonNode option : type) {
                if (!option.isTextual()) {
                    throw new IllegalStateException(
                            "AI Coding artifact contract contains a non-string type at "
                                    + path);
                }
                if (matchesSingleType(instance, option.asText(), path)) {
                    return true;
                }
            }
            return false;
        }
        throw new IllegalStateException(
                "AI Coding artifact contract contains an invalid type at " + path);
    }

    private boolean matchesSingleType(
            JsonNode instance,
            String type,
            String path) {
        return switch (type) {
            case "null" -> instance == null || instance.isNull();
            case "object" -> instance != null && instance.isObject();
            case "array" -> instance != null && instance.isArray();
            case "string" -> instance != null && instance.isTextual();
            case "boolean" -> instance != null && instance.isBoolean();
            case "integer" -> instance != null && instance.isIntegralNumber();
            case "number" -> instance != null && instance.isNumber();
            default -> throw new IllegalStateException(
                    "AI Coding artifact contract uses unsupported type '"
                            + type + "' at " + path);
        };
    }

    private ResolvedSchema resolveReference(
            String reference,
            JsonNode currentRoot) {
        int fragmentIndex = reference.indexOf('#');
        String fileName = fragmentIndex < 0
                ? reference
                : reference.substring(0, fragmentIndex);
        String fragment = fragmentIndex < 0
                ? ""
                : reference.substring(fragmentIndex + 1);

        JsonNode root = currentRoot;
        if (!fileName.isBlank()) {
            if (!fileName.matches("[A-Za-z0-9._-]+\\.schema\\.json")) {
                throw new IllegalStateException(
                        "AI Coding artifact contract contains an unsafe external $ref: "
                                + reference);
            }
            root = referencedSchemas.computeIfAbsent(
                    fileName,
                    resourceLoader::load);
        }
        JsonNode resolved = fragment.isBlank()
                ? root
                : root.at(fragment);
        if (resolved == null || resolved.isMissingNode()) {
            throw new IllegalStateException(
                    "AI Coding artifact contract contains an unresolved $ref: "
                            + reference);
        }
        return new ResolvedSchema(resolved, root);
    }

    private static void requireArrayKeyword(
            JsonNode keyword,
            String name,
            String path) {
        if (!keyword.isArray()) {
            throw new IllegalStateException(
                    "AI Coding artifact contract contains invalid "
                            + name + " at " + path);
        }
    }

    private static void requireMinimum(
            int actual,
            JsonNode limit,
            String unit,
            String path) {
        if (limit != null && actual < limit.asInt()) {
            throw invalid(
                    path,
                    "must contain at least " + limit.asInt() + " " + unit);
        }
    }

    private static void requireMaximum(
            int actual,
            JsonNode limit,
            String unit,
            String path) {
        if (limit != null && actual > limit.asInt()) {
            throw invalid(
                    path,
                    "must contain at most " + limit.asInt() + " " + unit);
        }
    }

    private static String childPath(String path, String field) {
        return path + "." + field;
    }

    private static IllegalArgumentException invalid(
            String path,
            String message) {
        return new IllegalArgumentException(
                "AI Coding artifact contract violation at "
                        + path + ": " + message);
    }

    private record ResolvedSchema(JsonNode schema, JsonNode rootSchema) {
    }
}
