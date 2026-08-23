package com.enterprise.ai.agent.graph;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Backend catalog for platform GraphSpec node capabilities.
 */
public enum AgentGraphNodeType {

    LLM("LLM", "llm", "action", NodeFamily.LLM, true),
    USER_INPUT("USER_INPUT", "userInput", "input", NodeFamily.FLOW, false),
    INTERACTION("INTERACTION", "interaction", "interaction", NodeFamily.FLOW, false),
    TOOL("TOOL", "tool", "action", NodeFamily.TOOL, true),
    IF_ELSE("IF_ELSE", "condition", "flow", NodeFamily.FLOW, false),
    VARIABLE_ASSIGN("VARIABLE_ASSIGN", "variable", "flow", NodeFamily.FLOW, false),
    TEMPLATE("TEMPLATE", "template", "flow", NodeFamily.FLOW, false),
    ANSWER("ANSWER", "answer", "response", NodeFamily.FLOW, false),
    CODE("CODE", "code", "compute", NodeFamily.FLOW, false),
    INTENT_CLASSIFIER("INTENT_CLASSIFIER", "classifier", "flow", NodeFamily.FLOW, false),
    VARIABLE_AGGREGATOR("VARIABLE_AGGREGATOR", "aggregate", "compute", NodeFamily.FLOW, false),
    HUMAN_APPROVAL("HUMAN_APPROVAL", "approval", "governance", NodeFamily.FLOW, false),
    LOOP("LOOP", "loop", "flow", NodeFamily.FLOW, false),
    KNOWLEDGE_WRITE("KNOWLEDGE_WRITE", "knowledgeWrite", "knowledge", NodeFamily.FLOW, false),
    DOCUMENT_EXTRACT("DOCUMENT_EXTRACT", "documentExtract", "data", NodeFamily.FLOW, false),
    MCP_CALL("MCP_CALL", "mcp", "integration", NodeFamily.FLOW, true),
    PAGE_ACTION("PAGE_ACTION", "pageAction", "integration", NodeFamily.FLOW, false),
    PARAMETER_EXTRACT("PARAMETER_EXTRACT", "parameter", "flow", NodeFamily.FLOW, false),
    HTTP_REQUEST("HTTP_REQUEST", "http", "integration", NodeFamily.FLOW, true),
    KNOWLEDGE_RETRIEVAL("KNOWLEDGE_RETRIEVAL", "knowledge", "knowledge", NodeFamily.FLOW, true);

    private static final Map<String, AgentGraphNodeType> LOOKUP = Arrays.stream(values())
            .flatMap(type -> type.lookupKeys().stream().map(key -> Map.entry(key, type)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left));

    private final String type;
    private final String canvasKind;
    private final String canvasCategory;
    private final NodeFamily family;
    private final boolean retryable;
    AgentGraphNodeType(String type,
                       String canvasKind,
                       String canvasCategory,
                       NodeFamily family,
                       boolean retryable) {
        this.type = type;
        this.canvasKind = canvasKind;
        this.canvasCategory = canvasCategory;
        this.family = family;
        this.retryable = retryable;
    }

    public static Optional<AgentGraphNodeType> find(String rawType) {
        return Optional.ofNullable(LOOKUP.get(key(rawType)));
    }

    public static String normalize(String rawType) {
        return find(rawType)
                .map(AgentGraphNodeType::type)
                .orElseGet(() -> rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT));
    }

    public static List<Descriptor> catalog() {
        return Arrays.stream(values())
                .map(type -> new Descriptor(
                        type.type,
                        type.canvasKind,
                        type.canvasCategory,
                        type.family.name(),
                        type.retryable))
                .toList();
    }

    public String type() {
        return type;
    }

    public String canvasKind() {
        return canvasKind;
    }

    public String canvasCategory() {
        return canvasCategory;
    }

    public boolean isLlm() {
        return family == NodeFamily.LLM;
    }

    public boolean isToolLike() {
        return family == NodeFamily.TOOL;
    }

    public boolean isFlow() {
        return family == NodeFamily.FLOW;
    }

    public boolean retryable() {
        return retryable;
    }

    private Set<String> lookupKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        keys.add(key(type));
        keys.add(key(canvasKind));
        return keys;
    }

    private static String key(String rawType) {
        return rawType == null ? "" : rawType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    public enum NodeFamily {
        LLM,
        TOOL,
        FLOW
    }

    public record Descriptor(String type,
                             String canvasKind,
                             String canvasCategory,
                             String family,
                             boolean retryable) {
    }
}
