package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.catalog.tool.CapabilityParameterContractCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Interprets the accepted declaration just before Console outbound HTTP.
 *
 * <p>The catalog deliberately retains the SDK's raw parallel dotted paths for
 * source/hash/diff purposes. This class builds an ephemeral tree from those
 * paths so the same declaration can be displayed and invoked without changing
 * the accepted contract. It never copies input values into a diagnostic.</p>
 */
final class ConsoleBusinessMethodInputValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DIAGNOSTICS = 20;
    private static final int MAX_PATH_LENGTH = 160;

    private ConsoleBusinessMethodInputValidator() {
    }

    static ValidationResult validate(ToolDefinitionEntity tool, Map<String, Object> input) {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        List<ToolDefinitionParameter> parameters = CapabilityParameterContractCodec.logicalTree(
                CapabilityParameterContractCodec.parse(JSON, tool == null ? null : tool.getParametersJson()));
        if (parameters.isEmpty()) {
            if (!safeInput.isEmpty()) fail("input", "DECLARATION_SHAPE_UNAVAILABLE");
            return ValidationResult.empty();
        }
        Declaration declaration = Declaration.from(parameters);
        Diagnostics diagnostics = new Diagnostics();
        if (declaration.singleDtoRoot()) {
            validateSingleDto(declaration.roots().get(0), safeInput, diagnostics);
        } else {
            validateRoots(declaration.roots(), safeInput, diagnostics);
        }
        return new ValidationResult(diagnostics.values());
    }

    private static void validateSingleDto(Node root, Map<String, Object> input, Diagnostics diagnostics) {
        boolean wrapped = input.containsKey(root.name());
        Set<String> childNames = root.children().keySet();
        boolean hasFlatFields = input.keySet().stream().anyMatch(childNames::contains);
        if (wrapped && hasFlatFields) fail(root.name(), "MIXED_DTO_BINDING");
        if (wrapped) {
            for (String name : input.keySet()) {
                if (!root.name().equals(name)) fail(name, "UNDECLARED_FIELD");
            }
            validateNode(root, input.get(root.name()), true, root.name(), diagnostics);
            return;
        }
        for (String name : input.keySet()) {
            if (!childNames.contains(name)) fail(name, "UNDECLARED_FIELD");
        }
        if (root.required() && input.isEmpty()) fail(root.name(), "REQUIRED");
        validateChildren(root, input, "", diagnostics, true);
    }

    private static void validateRoots(List<Node> roots, Map<String, Object> input, Diagnostics diagnostics) {
        Map<String, Node> declared = new LinkedHashMap<>();
        for (Node root : roots) declared.put(root.name(), root);
        for (String name : input.keySet()) {
            if (!declared.containsKey(name)) fail(name, "UNDECLARED_FIELD");
        }
        for (Node root : roots) {
            boolean present = input.containsKey(root.name());
            validateNode(root, present ? input.get(root.name()) : null, present, root.name(), diagnostics);
        }
    }

    private static void validateNode(Node node,
                                     Object value,
                                     boolean present,
                                     String path,
                                     Diagnostics diagnostics) {
        if (!present || value == null) {
            if (node.required()) fail(path, "REQUIRED");
            return;
        }
        String type = normalizedType(node.type());
        if (!recognized(type)) fail(path, "UNSUPPORTED_DECLARATION_TYPE");
        validateType(type, value, path);
        constraints(node.metadata(), value, path);
        if (isObject(type)) {
            if (!node.children().isEmpty()) {
                validateChildren(node, asMap(value), path, diagnostics, false);
            } else if (closedObject(node.metadata())) {
                validateChildren(node, asMap(value), path, diagnostics, false);
            }
        } else if (isArray(type)) {
            validateArrayElements(node, values(value), path, diagnostics);
        }
    }

    private static void validateChildren(Node parent,
                                         Map<String, Object> value,
                                         String parentPath,
                                         Diagnostics diagnostics,
                                         boolean flatDto) {
        Map<String, Node> children = parent.children();
        for (String name : value.keySet()) {
            if (!children.containsKey(name)) {
                String path = flatDto ? name : childPath(parentPath, name);
                fail(path, "UNDECLARED_FIELD");
            }
        }
        for (Node child : children.values()) {
            boolean present = value.containsKey(child.name());
            String path = flatDto ? child.name() : childPath(parentPath, child.name());
            validateNode(child, present ? value.get(child.name()) : null, present, path, diagnostics);
        }
    }

    private static void validateArrayElements(Node node,
                                              List<Object> elements,
                                              String path,
                                              Diagnostics diagnostics) {
        Map<String, Object> metadata = metadata(node.metadata());
        String itemsType = normalizedType(text(metadata.get("itemsType")));
        Object rawItemsMetadata = metadata.get("itemsMetadata");
        if (!node.children().isEmpty()) {
            for (int index = 0; index < elements.size(); index++) {
                Object element = elements.get(index);
                if (!(element instanceof Map<?, ?>)) fail(path + "[" + index + "]", "TYPE_OBJECT");
                validateChildren(node, asMap(element), path + "[" + index + "]", diagnostics, false);
            }
            return;
        }
        if (!StringUtils.hasText(itemsType)) {
            diagnostics.add(path + "[]", "ARRAY_ELEMENT_SHAPE_UNSPECIFIED");
            return;
        }
        if (!recognized(itemsType)) fail(path + "[]", "UNSUPPORTED_ARRAY_ELEMENT_TYPE");
        boolean openObjectShape = isObject(itemsType);
        for (int index = 0; index < elements.size(); index++) {
            String itemPath = path + "[" + index + "]";
            Object element = elements.get(index);
            validateType(itemsType, element, itemPath);
            constraints(rawItemsMetadata, element, itemPath);
        }
        if (openObjectShape && !elements.isEmpty()) diagnostics.add(path + "[]", "ARRAY_OBJECT_SHAPE_UNSPECIFIED");
    }

    private static String childPath(String parentPath, String childName) {
        return StringUtils.hasText(parentPath) ? parentPath + "." + childName : childName;
    }

    private static void validateType(String type, Object value, String path) {
        if (isString(type) && !(value instanceof CharSequence)) fail(path, "TYPE_STRING");
        if (isInteger(type) && !integer(value)) fail(path, "TYPE_INTEGER");
        if (isNumber(type) && !(value instanceof Number)) fail(path, "TYPE_NUMBER");
        if (isBoolean(type) && !(value instanceof Boolean)) fail(path, "TYPE_BOOLEAN");
        if (isObject(type) && !(value instanceof Map<?, ?>)) fail(path, "TYPE_OBJECT");
        if (isArray(type) && !collection(value)) fail(path, "TYPE_ARRAY");
    }

    private static void constraints(Object rawMetadata, Object value, String path) {
        Map<String, Object> metadata = metadata(rawMetadata);
        if (metadata.isEmpty()) return;
        Object constant = metadata.get("const");
        if (constant != null && !same(constant, value)) fail(path, "CONST_MISMATCH");
        Object allowed = metadata.get("enum");
        if (allowed instanceof Iterable<?> entries) {
            boolean match = false;
            for (Object entry : entries) {
                if (same(entry, value)) {
                    match = true;
                    break;
                }
            }
            if (!match) fail(path, "ENUM_MISMATCH");
        }
        if (value instanceof CharSequence text) {
            length(path, text.length(), metadata, "minLength", "maxLength");
            Object pattern = metadata.get("pattern");
            if (pattern instanceof String expression) {
                try {
                    if (!Pattern.compile(expression).matcher(text).matches()) fail(path, "PATTERN_MISMATCH");
                } catch (java.util.regex.PatternSyntaxException invalid) {
                    fail(path, "UNSUPPORTED_DECLARATION_PATTERN");
                }
            }
        }
        if (value instanceof Number number) {
            BigDecimal actual = decimal(number);
            if (actual == null) fail(path, "NUMBER_NOT_FINITE");
            numeric(path, actual, metadata);
        }
        if (collection(value)) length(path, values(value).size(), metadata, "minItems", "maxItems");
    }

    private static void numeric(String path, BigDecimal actual, Map<String, Object> metadata) {
        compare(path, actual, metadata.get("minimum"), false, "BELOW_MINIMUM", false);
        compare(path, actual, metadata.get("maximum"), false, "ABOVE_MAXIMUM", true);
        compare(path, actual, metadata.get("exclusiveMinimum"), true, "BELOW_EXCLUSIVE_MINIMUM", false);
        compare(path, actual, metadata.get("exclusiveMaximum"), true, "ABOVE_EXCLUSIVE_MAXIMUM", true);
        Object multipleOf = metadata.get("multipleOf");
        if (multipleOf != null) {
            BigDecimal divisor = decimal(multipleOf);
            if (divisor == null || BigDecimal.ZERO.compareTo(divisor) == 0) fail(path, "UNSUPPORTED_DECLARATION_MULTIPLE_OF");
            if (actual.remainder(divisor).compareTo(BigDecimal.ZERO) != 0) fail(path, "MULTIPLE_OF_MISMATCH");
        }
    }

    private static void compare(String path, BigDecimal actual, Object raw, boolean exclusive, String reason, boolean maximum) {
        if (raw == null) return;
        BigDecimal boundary = decimal(raw);
        if (boundary == null) fail(path, "UNSUPPORTED_DECLARATION_BOUNDARY");
        int compared = actual.compareTo(boundary);
        if ((!maximum && (compared < 0 || exclusive && compared == 0))
                || (maximum && (compared > 0 || exclusive && compared == 0))) fail(path, reason);
    }

    private static void length(String path, int length, Map<String, Object> metadata, String minKey, String maxKey) {
        Integer min = integerValue(metadata.get(minKey));
        Integer max = integerValue(metadata.get(maxKey));
        if (min != null && length < min) fail(path, "BELOW_" + minKey.toUpperCase(Locale.ROOT));
        if (max != null && length > max) fail(path, "ABOVE_" + maxKey.toUpperCase(Locale.ROOT));
    }

    private static boolean closedObject(Object rawMetadata) {
        Object open = metadata(rawMetadata).get("openObject");
        return Boolean.FALSE.equals(open) || "false".equalsIgnoreCase(String.valueOf(open));
    }

    private static boolean recognized(String type) {
        return isString(type) || isInteger(type) || isNumber(type) || isBoolean(type) || isObject(type) || isArray(type);
    }

    private static String normalizedType(String type) {
        return StringUtils.hasText(type) ? type.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static boolean isString(String type) {
        return List.of("string", "text", "char", "character", "java.lang.string", "uuid", "date", "datetime", "date-time").contains(type);
    }

    private static boolean isInteger(String type) {
        return List.of("integer", "int", "long", "short", "byte", "java.lang.integer", "java.lang.long").contains(type);
    }

    private static boolean isNumber(String type) {
        return isInteger(type) || List.of("number", "decimal", "double", "float", "bigdecimal", "java.math.bigdecimal").contains(type);
    }

    private static boolean isBoolean(String type) {
        return List.of("boolean", "bool", "java.lang.boolean").contains(type);
    }

    private static boolean isObject(String type) {
        return List.of("object", "map", "json", "jsonobject").contains(type);
    }

    private static boolean isArray(String type) {
        return List.of("array", "list", "set", "collection").contains(type);
    }

    private static boolean integer(Object value) {
        if (!(value instanceof Number number)) return false;
        BigDecimal decimal = decimal(number);
        return decimal != null && decimal.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal decimal(Object value) {
        try {
            return value instanceof Number || value instanceof String ? new BigDecimal(String.valueOf(value)) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Integer integerValue(Object value) {
        BigDecimal decimal = decimal(value);
        if (decimal == null || decimal.stripTrailingZeros().scale() > 0) return null;
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException invalid) {
            return null;
        }
    }

    private static boolean collection(Object value) {
        return value instanceof Iterable<?> || value != null && value.getClass().isArray();
    }

    private static List<Object> values(Object value) {
        List<Object> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) result.add(item);
        } else if (value != null && value.getClass().isArray()) {
            int size = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < size; index++) result.add(java.lang.reflect.Array.get(value, index));
        }
        return result;
    }

    private static Map<String, Object> asMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> metadata(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean same(Object left, Object right) {
        JsonNode a = JSON.valueToTree(left);
        JsonNode b = JSON.valueToTree(right);
        return a.equals(b);
    }

    private static void fail(String path, String reason) {
        throw new InvalidInputException(List.of(new InputDiagnostic(path, reason)));
    }

    record ValidationResult(List<InputDiagnostic> diagnostics) {
        ValidationResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        static ValidationResult empty() {
            return new ValidationResult(List.of());
        }

        Map<String, Object> safeMetadata() {
            if (diagnostics.isEmpty()) return Map.of();
            List<Map<String, String>> values = new ArrayList<>();
            for (InputDiagnostic diagnostic : diagnostics) values.add(diagnostic.safeValue());
            return Map.of("inputDiagnostics", List.copyOf(values));
        }
    }

    record InputDiagnostic(String path, String reason) {
        InputDiagnostic {
            path = safePath(path);
            reason = safeReason(reason);
        }

        Map<String, String> safeValue() {
            return Map.of("path", path, "reason", reason);
        }
    }

    static final class InvalidInputException extends RuntimeException {
        private final List<InputDiagnostic> diagnostics;

        InvalidInputException(List<InputDiagnostic> diagnostics) {
            super(message(diagnostics));
            this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        List<InputDiagnostic> diagnostics() {
            return diagnostics;
        }

        Map<String, Object> safeMetadata() {
            return new ValidationResult(diagnostics).safeMetadata();
        }

        private static String message(List<InputDiagnostic> diagnostics) {
            InputDiagnostic first = diagnostics == null || diagnostics.isEmpty()
                    ? new InputDiagnostic("input", "INVALID_INPUT") : diagnostics.get(0);
            return "输入字段无效: " + first.path() + " (" + first.reason() + ")";
        }
    }

    private static String safePath(String value) {
        String source = StringUtils.hasText(value) ? value.trim() : "input";
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < source.length() && safe.length() < MAX_PATH_LENGTH; index++) {
            char character = source.charAt(index);
            safe.append(Character.isLetterOrDigit(character) || character == '.' || character == '[' || character == ']'
                    || character == '_' || character == '-' ? character : '_');
        }
        return safe.isEmpty() ? "input" : safe.toString();
    }

    private static String safeReason(String value) {
        String source = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "INVALID_INPUT";
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < source.length() && safe.length() < 80; index++) {
            char character = source.charAt(index);
            safe.append(Character.isLetterOrDigit(character) || character == '_' ? character : '_');
        }
        return safe.isEmpty() ? "INVALID_INPUT" : safe.toString();
    }

    private static final class Diagnostics {
        private final LinkedHashSet<InputDiagnostic> values = new LinkedHashSet<>();

        void add(String path, String reason) {
            if (values.size() < MAX_DIAGNOSTICS) values.add(new InputDiagnostic(path, reason));
        }

        List<InputDiagnostic> values() {
            return List.copyOf(values);
        }
    }

    private static final class Declaration {
        private final List<Node> roots;

        private Declaration(List<Node> roots) {
            this.roots = roots;
        }

        static Declaration from(List<ToolDefinitionParameter> parameters) {
            Map<String, Node> roots = new LinkedHashMap<>();
            for (ToolDefinitionParameter parameter : parameters) {
                if (parameter == null || !StringUtils.hasText(parameter.name())) continue;
                String[] path = parameter.name().trim().split("\\.");
                if (path.length == 0 || !StringUtils.hasText(path[0])) continue;
                Node current = roots.computeIfAbsent(path[0], Node::synthetic);
                for (int index = 1; index < path.length; index++) {
                    if (!StringUtils.hasText(path[index])) continue;
                    current = current.children.computeIfAbsent(path[index], Node::synthetic);
                }
                current.parameter = parameter;
                attachDeclaredChildren(current, parameter.children());
            }
            return new Declaration(List.copyOf(roots.values()));
        }

        private static void attachDeclaredChildren(Node parent, List<ToolDefinitionParameter> parameters) {
            if (parameters == null) return;
            for (ToolDefinitionParameter parameter : parameters) {
                if (parameter == null || !StringUtils.hasText(parameter.name())) continue;
                Node child = parent.children.computeIfAbsent(parameter.name().trim(), Node::synthetic);
                child.parameter = parameter;
                attachDeclaredChildren(child, parameter.children());
            }
        }

        List<Node> roots() {
            return roots;
        }

        boolean singleDtoRoot() {
            return roots.size() == 1 && isObject(normalizedType(roots.get(0).type())) && !roots.get(0).children().isEmpty();
        }
    }

    private static final class Node {
        private final String name;
        private ToolDefinitionParameter parameter;
        private final Map<String, Node> children = new LinkedHashMap<>();

        private Node(String name, ToolDefinitionParameter parameter) {
            this.name = name;
            this.parameter = parameter;
        }

        static Node synthetic(String name) {
            return new Node(name, new ToolDefinitionParameter(name, "object", null, false, null, List.of(), null));
        }

        String name() {
            return name;
        }

        String type() {
            return parameter == null ? "object" : parameter.type();
        }

        boolean required() {
            return parameter != null && parameter.required();
        }

        Object metadata() {
            return parameter == null ? null : parameter.metadata();
        }

        Map<String, Node> children() {
            return children;
        }
    }
}
