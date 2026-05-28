package com.enterprise.ai.spring.registry;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * SDK-side GraphSpec node type catalog.
 */
public enum EafGraphNodeType {

    LLM("LLM", "llm", "action"),
    USER_INPUT("USER_INPUT", "userInput", "input", "user_input", "input", "form_input"),
    INTERACTION("INTERACTION", "interaction", "interaction", "collect_input", "present_output", "user_choice", "confirm_action"),
    TOOL("TOOL", "tool", "action"),
    CAPABILITY("CAPABILITY", "skill", "action"),
    IF_ELSE("IF_ELSE", "condition", "flow", "if_else", "condition"),
    VARIABLE_ASSIGN("VARIABLE_ASSIGN", "variable", "flow", "variable_assign", "variable"),
    TEMPLATE("TEMPLATE", "template", "flow"),
    ANSWER("ANSWER", "answer", "response", "reply"),
    CODE("CODE", "code", "compute"),
    INTENT_CLASSIFIER("INTENT_CLASSIFIER", "classifier", "flow", "intent_classifier", "classifier", "question_classifier"),
    VARIABLE_AGGREGATOR("VARIABLE_AGGREGATOR", "aggregate", "compute", "variable_aggregator", "aggregate"),
    HUMAN_APPROVAL("HUMAN_APPROVAL", "approval", "governance", "human_approval", "approval"),
    LOOP("LOOP", "loop", "flow"),
    KNOWLEDGE_WRITE("KNOWLEDGE_WRITE", "knowledgeWrite", "knowledge", "knowledge_write"),
    DOCUMENT_EXTRACT("DOCUMENT_EXTRACT", "documentExtract", "data", "document_extract", "document"),
    MCP_CALL("MCP_CALL", "mcp", "integration", "mcp_call", "mcp"),
    PAGE_ACTION("PAGE_ACTION", "pageAction", "integration", "page_action", "ui_action", "client_action"),
    PARAMETER_EXTRACT("PARAMETER_EXTRACT", "parameter", "flow", "parameter_extract", "parameter"),
    HTTP_REQUEST("HTTP_REQUEST", "http", "integration", "http_request", "http"),
    KNOWLEDGE_RETRIEVAL("KNOWLEDGE_RETRIEVAL", "knowledge", "knowledge", "knowledge_retrieval", "knowledge");

    private static final Map<String, EafGraphNodeType> LOOKUP = Arrays.stream(values())
            .flatMap(type -> type.lookupKeys().stream().map(key -> Map.entry(key, type)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left));

    private final String type;
    private final String canvasKind;
    private final String category;
    private final List<String> aliases;

    EafGraphNodeType(String type, String canvasKind, String category, String... aliases) {
        this.type = type;
        this.canvasKind = canvasKind;
        this.category = category;
        this.aliases = Arrays.stream(aliases).map(EafGraphNodeType::key).toList();
    }

    public static Optional<EafGraphNodeType> find(String rawType) {
        return Optional.ofNullable(LOOKUP.get(key(rawType)));
    }

    public static String normalize(String rawType) {
        return find(rawType)
                .map(EafGraphNodeType::type)
                .orElseGet(() -> rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT));
    }

    public static List<String> supportedTypes() {
        return Arrays.stream(values()).map(EafGraphNodeType::type).toList();
    }

    public String type() {
        return type;
    }

    public String canvasKind() {
        return canvasKind;
    }

    public String category() {
        return category;
    }

    private List<String> lookupKeys() {
        List<String> keys = new java.util.ArrayList<>(aliases);
        keys.add(key(type));
        keys.add(key(canvasKind));
        return keys;
    }

    private static String key(String rawType) {
        return rawType == null ? "" : rawType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
