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

    LLM("LLM", "llm", "action", NodeFamily.LLM, true, "llm", "model"),
    TOOL("TOOL", "tool", "action", NodeFamily.TOOL, true, "tool"),
    CAPABILITY("CAPABILITY", "skill", "action", NodeFamily.TOOL, true, "capability", "skill"),
    IF_ELSE("IF_ELSE", "condition", "flow", NodeFamily.FLOW, false, "if_else", "condition"),
    VARIABLE_ASSIGN("VARIABLE_ASSIGN", "variable", "flow", NodeFamily.FLOW, false, "variable_assign", "variable"),
    TEMPLATE("TEMPLATE", "template", "flow", NodeFamily.FLOW, false, "template"),
    ANSWER("ANSWER", "answer", "response", NodeFamily.FLOW, false, "answer", "reply"),
    CODE("CODE", "code", "compute", NodeFamily.FLOW, false, "code"),
    INTENT_CLASSIFIER("INTENT_CLASSIFIER", "classifier", "flow", NodeFamily.FLOW, false,
            "intent_classifier", "classifier", "question_classifier"),
    VARIABLE_AGGREGATOR("VARIABLE_AGGREGATOR", "aggregate", "compute", NodeFamily.FLOW, false,
            "variable_aggregator", "aggregate"),
    HUMAN_APPROVAL("HUMAN_APPROVAL", "approval", "governance", NodeFamily.FLOW, false,
            "human_approval", "approval"),
    LOOP("LOOP", "loop", "flow", NodeFamily.FLOW, false, "loop"),
    KNOWLEDGE_WRITE("KNOWLEDGE_WRITE", "knowledgeWrite", "knowledge", NodeFamily.FLOW, false,
            "knowledge_write", "knowledge_write"),
    DOCUMENT_EXTRACT("DOCUMENT_EXTRACT", "documentExtract", "data", NodeFamily.FLOW, false,
            "document_extract", "document"),
    MCP_CALL("MCP_CALL", "mcp", "integration", NodeFamily.FLOW, true, "mcp_call", "mcp"),
    PARAMETER_EXTRACT("PARAMETER_EXTRACT", "parameter", "flow", NodeFamily.FLOW, false,
            "parameter_extract", "parameter"),
    HTTP_REQUEST("HTTP_REQUEST", "http", "integration", NodeFamily.FLOW, true, "http_request", "http"),
    KNOWLEDGE_RETRIEVAL("KNOWLEDGE_RETRIEVAL", "knowledge", "knowledge", NodeFamily.FLOW, true,
            "knowledge_retrieval", "knowledge");

    private static final Map<String, AgentGraphNodeType> LOOKUP = Arrays.stream(values())
            .flatMap(type -> type.lookupKeys().stream().map(key -> Map.entry(key, type)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left));

    private final String type;
    private final String canvasKind;
    private final String canvasCategory;
    private final NodeFamily family;
    private final boolean retryable;
    private final Set<String> aliases;

    AgentGraphNodeType(String type,
                       String canvasKind,
                       String canvasCategory,
                       NodeFamily family,
                       boolean retryable,
                       String... aliases) {
        this.type = type;
        this.canvasKind = canvasKind;
        this.canvasCategory = canvasCategory;
        this.family = family;
        this.retryable = retryable;
        this.aliases = Arrays.stream(aliases)
                .map(AgentGraphNodeType::key)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Optional<AgentGraphNodeType> find(String rawType) {
        return Optional.ofNullable(LOOKUP.get(key(rawType)));
    }

    public static String normalize(String rawType) {
        return find(rawType)
                .map(AgentGraphNodeType::type)
                .orElseGet(() -> rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean supports(String rawType) {
        return find(rawType).isPresent();
    }

    public static List<Descriptor> catalog() {
        return Arrays.stream(values())
                .map(type -> new Descriptor(
                        type.type,
                        type.canvasKind,
                        type.canvasCategory,
                        type.family.name(),
                        type.retryable,
                        type.aliases.stream().sorted().toList()))
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
        Set<String> keys = new java.util.LinkedHashSet<>(aliases);
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
                             boolean retryable,
                             List<String> aliases) {
    }
}
