package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.response.BusinessResponseEnvelope;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeHit;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult;
import com.enterprise.ai.runtime.eval.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient.HttpExecutionRequest;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient.HttpExecutionResult;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Executes read-oriented Knowledge and governed HTTP GraphSpec nodes. */
final class RuntimeIoNodeHandlers {

    private static final Set<String> KNOWLEDGE_DIAGNOSTIC_KEYS = Set.of(
            "knowledgeBaseCount",
            "failedKnowledgeBaseCount",
            "vectorRawCandidateCount",
            "vectorAcceptedCandidateCount",
            "keywordRawCandidateCount",
            "keywordAcceptedCandidateCount",
            "preMergeCandidateCount",
            "mergedCandidateCount",
            "rerankedCandidateCount",
            "scoreAcceptedCandidateCount",
            "scoreFilteredCandidateCount",
            "topKTruncatedCandidateCount",
            "returnedCandidateCount",
            "returnedHitCount",
            "returnedContentChars",
            "contentTruncatedCount",
            "budgetOmittedHitCount",
            "contentBudgetExhausted",
            "perHitContentLimit",
            "totalContentLimit",
            "retrievalStageMs",
            "rerankStageMs",
            "totalCostMs");

    private final ObjectMapper objectMapper;
    private final RuntimeKnowledgeRetrievalClient knowledgeClient;
    private final WorkflowHttpClient httpClient;
    private final RuntimeNodeValueResolver valueResolver;

    RuntimeIoNodeHandlers(ObjectMapper objectMapper,
                          RuntimeKnowledgeRetrievalClient knowledgeClient,
                          WorkflowHttpClient httpClient,
                          RuntimeNodeValueResolver valueResolver) {
        this.objectMapper = objectMapper;
        this.knowledgeClient = knowledgeClient;
        this.httpClient = httpClient;
        this.valueResolver = valueResolver;
    }

    RuntimeGraphSpecExecutionResult executeKnowledgeRetrieval(GraphSpec.Node node,
                                                              RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        if (knowledgeClient == null) {
            return failure("RUNTIME_KNOWLEDGE_CLIENT_UNAVAILABLE",
                    "Knowledge retrieval client is unavailable", node.getId(), "KNOWLEDGE_RETRIEVAL");
        }
        Map<String, Object> config = mergedConfig(node, "knowledgeConfig");
        List<String> codes = stringList(config.get("knowledgeBaseCodes"));
        if (codes.isEmpty()) {
            return failure("RUNTIME_KNOWLEDGE_BASE_REQUIRED",
                    "KNOWLEDGE_RETRIEVAL requires knowledgeBaseCodes", node.getId(), "KNOWLEDGE_RETRIEVAL");
        }
        String queryExpression = firstText(text(config.get("query")), "input");
        String query = inputText(queryExpression, context);
        if (!StringUtils.hasText(query)) {
            return failure("RUNTIME_KNOWLEDGE_QUERY_REQUIRED",
                    "KNOWLEDGE_RETRIEVAL query resolved to empty", node.getId(), "KNOWLEDGE_RETRIEVAL");
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "KNOWLEDGE_RETRIEVAL");
        }
        int topK = Math.max(1, Math.min(20, intValue(config.get("topK"), 5)));
        Float threshold = config.get("similarityThreshold") == null
                ? null : (float) doubleValue(config.get("similarityThreshold"), 0.5D);
        boolean rerankRequested = !(config.get("rerankEnabled") instanceof Boolean enabled) || enabled;
        String evidencePolicy = normalizeKnowledgeEvidencePolicy(config.get("evidencePolicy"));
        WorkflowExecutionIdentity identity = RuntimeTrustedExecutionContexts.identity(context);
        if (!identity.canResolveUserAcl()) {
            return failure("RUNTIME_KNOWLEDGE_USER_IDENTITY_REQUIRED",
                    "KNOWLEDGE_RETRIEVAL requires a trusted user identity for ACL",
                    node.getId(), "KNOWLEDGE_RETRIEVAL");
        }
        try {
            KnowledgeRetrievalResult result = knowledgeClient.retrieve(KnowledgeRetrievalRequest.builder()
                    .query(query)
                    .knowledgeBaseCodes(codes)
                    .userId(identity.userId())
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .searchMode(text(config.get("searchMode")))
                    .rerankEnabled(rerankRequested)
                    .build());
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "KNOWLEDGE_RETRIEVAL");
            }
            if (result == null || (result.getCode() != 0 && result.getCode() != 200)) {
                return failure("RUNTIME_KNOWLEDGE_FAILED",
                        result == null ? "Knowledge service returned empty response"
                                : firstText(result.getMessage(), "Knowledge retrieval failed"),
                        node.getId(), "KNOWLEDGE_RETRIEVAL");
            }
            KnowledgeRetrievalData data = result.getData();
            List<KnowledgeHit> hits = data == null || data.getHits() == null ? List.of() : data.getHits();
            boolean empty = hits.isEmpty();
            String outcome = empty ? "NO_EVIDENCE" : "HIT";
            Map<String, Object> diagnostics = data == null || data.getDiagnostics() == null
                    ? Map.of() : sanitizeKnowledgeDiagnostics(data.getDiagnostics());
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("query", query);
            structured.put("hits", hits);
            structured.put("hitCount", hits.size());
            structured.put("outcome", outcome);
            structured.put("empty", empty);
            if (!diagnostics.isEmpty()) {
                structured.put("diagnostics", diagnostics);
            }
            Map<String, Object> metadata = nodeMetadata(node, "KNOWLEDGE_RETRIEVAL");
            metadata.put("structuredOutput", structured);
            metadata.put("evidencePolicy", evidencePolicy);
            metadata.put("retrievalOutcome", outcome);
            metadata.put("empty", empty);
            if ("REQUIRED".equals(evidencePolicy)) {
                metadata.put("route", empty ? "no_evidence" : "evidence");
            }
            Map<String, Object> knowledgeTrace = new LinkedHashMap<>();
            knowledgeTrace.put("queryLength", query.length());
            knowledgeTrace.put("hitCount", hits.size());
            knowledgeTrace.put("topK", topK);
            knowledgeTrace.put("searchMode", firstText(text(config.get("searchMode")), "hybrid"));
            knowledgeTrace.put("rerankApplied", rerankRequested);
            knowledgeTrace.put("evidencePolicy", evidencePolicy);
            knowledgeTrace.put("outcome", outcome);
            knowledgeTrace.put("empty", empty);
            if ("REQUIRED".equals(evidencePolicy)) {
                knowledgeTrace.put("route", empty ? "no_evidence" : "evidence");
            }
            copyKnowledgeDiagnostics(knowledgeTrace, diagnostics);
            metadata.put("traceSummary", knowledgeTrace);
            String answer;
            try {
                answer = objectMapper.writeValueAsString(structured);
            } catch (Exception ex) {
                answer = "hitCount=" + hits.size();
            }
            return success(node, "KNOWLEDGE_RETRIEVAL", answer, metadata);
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "KNOWLEDGE_RETRIEVAL");
            }
            return failure("RUNTIME_KNOWLEDGE_FAILED",
                    "KNOWLEDGE_RETRIEVAL failed: " + ex.getMessage(), node.getId(), "KNOWLEDGE_RETRIEVAL");
        }
    }

    RuntimeGraphSpecExecutionResult executeHttpRequest(GraphSpec.Node node,
                                                       RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        RuntimeEvalExecutionContext evaluation = RuntimeTrustedExecutionContexts.evaluation(context);
        if (evaluation.blocksRawExternalCalls()) {
            return evalSideEffectBlocked(node, "HTTP_REQUEST",
                    "Eval execution blocks raw HTTP_REQUEST nodes until a sandbox HTTP adapter is configured",
                    evaluation);
        }
        if (httpClient == null) {
            return failure("RUNTIME_HTTP_CLIENT_UNAVAILABLE", "HTTP client is unavailable",
                    node.getId(), "HTTP_REQUEST");
        }
        Map<String, Object> config = httpConfig(node);
        String method = firstText(text(config.get("method")), "GET");
        String urlTemplate = text(config.get("url"));
        if (!StringUtils.hasText(urlTemplate)) {
            return failure("RUNTIME_HTTP_URL_REQUIRED", "HTTP_REQUEST requires url",
                    node.getId(), "HTTP_REQUEST");
        }
        String url = firstText(valueResolver.renderTemplate(urlTemplate, context), urlTemplate);
        Map<String, String> queryParams = renderStringMap(mapValue(config.get("queryParams")), context);
        Map<String, String> headers = renderStringMap(mapValue(config.get("headers")), context);
        String bodyType = firstText(text(config.get("bodyType")), "none");
        String body = text(config.get("body"));
        if (StringUtils.hasText(body) && body.contains("{{")) {
            body = valueResolver.renderTemplate(body, context);
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "HTTP_REQUEST");
        }
        HttpExecutionResult result = httpClient.execute(new HttpExecutionRequest(
                method,
                url,
                queryParams,
                headers,
                bodyType,
                body,
                intValue(config.get("timeoutMs"), WorkflowHttpClient.DEFAULT_TIMEOUT_MS),
                text(config.get("credentialRef")),
                RuntimeTrustedExecutionContexts.identity(context)));
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "HTTP_REQUEST");
        }
        Map<String, Object> metadata = nodeMetadata(node, "HTTP_REQUEST");
        metadata.put("traceSummary", result.traceSummary());
        metadata.put("retryableFailure", result.retryableFailure());
        if (!result.success()) {
            metadata.put("structuredOutput", result.structuredOutput());
            return new RuntimeGraphSpecExecutionResult(
                    false, result.code(), result.body(), node.getId(), "HTTP_REQUEST",
                    List.of(step("execute-node", node.getId())), metadata);
        }
        Map<String, Object> structured = result.structuredOutput();
        metadata.put("structuredOutput", structured);
        Optional<BusinessResponseEnvelope.Failure> businessFailure =
                BusinessResponseEnvelope.failure(result.parsedBody());
        if (businessFailure.isPresent()) {
            BusinessResponseEnvelope.Failure failure = businessFailure.get();
            metadata.put("retryableFailure", false);
            if (failure.businessCode() != null) {
                metadata.put("businessCode", failure.businessCode());
            }
            return new RuntimeGraphSpecExecutionResult(
                    false, "RUNTIME_HTTP_BUSINESS_RESPONSE_FAILED", failure.message(),
                    node.getId(), "HTTP_REQUEST", List.of(step("execute-node", node.getId())), metadata);
        }
        String answer;
        try {
            answer = objectMapper.writeValueAsString(structured);
        } catch (Exception ex) {
            answer = "statusCode=" + result.statusCode();
        }
        return success(node, "HTTP_REQUEST", answer, metadata);
    }

    boolean allowsAutomaticHttpRetry(GraphSpec.Node node) {
        Map<String, Object> config = httpConfig(node);
        String method = firstText(text(config.get("method")), "GET");
        boolean allowNonIdempotent = Boolean.TRUE.equals(config.get("retryAllowNonIdempotent"));
        return httpClient == null || httpClient.isIdempotentMethod(method) || allowNonIdempotent;
    }

    boolean isRetryableHttpStatus(int status) {
        return httpClient != null && httpClient.isRetryableStatus(status);
    }

    private String inputText(String expression, Map<String, Object> context) {
        if (!StringUtils.hasText(expression)) {
            return valueResolver.userInputText(context);
        }
        if (expression.contains("{{")) {
            return firstText(valueResolver.renderTemplate(expression, context), "");
        }
        if ("input".equals(expression.trim()) || "userInput".equals(expression.trim())
                || "query".equals(expression.trim())) {
            return valueResolver.userInputText(context);
        }
        Object value = valueResolver.resolveContextValue(expression, context);
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeKnowledgeEvidencePolicy(Object rawPolicy) {
        String policy = firstText(text(rawPolicy), "OPTIONAL").toUpperCase(Locale.ROOT);
        return "REQUIRED".equals(policy) ? "REQUIRED" : "OPTIONAL";
    }

    private void copyKnowledgeDiagnostics(Map<String, Object> trace, Map<String, Object> diagnostics) {
        if (trace == null || diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        for (String key : KNOWLEDGE_DIAGNOSTIC_KEYS) {
            Object value = diagnostics.get(key);
            if (value instanceof Number || value instanceof Boolean) {
                trace.put(key, value);
            }
        }
    }

    private Map<String, Object> sanitizeKnowledgeDiagnostics(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (String key : KNOWLEDGE_DIAGNOSTIC_KEYS) {
            Object value = diagnostics.get(key);
            if (value instanceof Number || value instanceof Boolean) {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    private Map<String, Object> httpConfig(GraphSpec.Node node) {
        return mergedConfig(node, "httpConfig");
    }

    private Map<String, Object> mergedConfig(GraphSpec.Node node, String nestedKey) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get(nestedKey));
        if (nested == null || nested.isEmpty()) {
            return config;
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        config.forEach((key, value) -> {
            if (!nestedKey.equals(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private Map<String, String> renderStringMap(Map<String, Object> source, Map<String, Object> context) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (source == null) {
            return rendered;
        }
        source.forEach((key, value) -> {
            if (StringUtils.hasText(key)) {
                Object resolved = valueResolver.renderInputValue(value, context);
                rendered.put(key, resolved == null ? "" : String.valueOf(resolved));
            }
        });
        return rendered;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> items) {
            return items.stream().map(this::text).filter(StringUtils::hasText).toList();
        }
        if (value instanceof String raw && StringUtils.hasText(raw)) {
            return List.of(raw.trim());
        }
        return List.of();
    }

    private RuntimeGraphSpecExecutionResult success(GraphSpec.Node node,
                                                    String nodeType,
                                                    String answer,
                                                    Map<String, Object> metadata) {
        return new RuntimeGraphSpecExecutionResult(
                true, "RUNTIME_GRAPH_EXECUTED", answer, node.getId(), nodeType,
                List.of(step("execute-node", node.getId())), metadata);
    }

    private RuntimeGraphSpecExecutionResult cancelled(String nodeId, String nodeType) {
        return failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled", nodeId, nodeType);
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(nodeId)) metadata.put("nodeId", nodeId);
        if (StringUtils.hasText(nodeType)) metadata.put("nodeType", nodeType);
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, nodeType, List.of(), metadata);
    }

    private RuntimeGraphSpecExecutionResult evalSideEffectBlocked(GraphSpec.Node node,
                                                                  String nodeType,
                                                                  String message,
                                                                  RuntimeEvalExecutionContext evaluation) {
        Map<String, Object> metadata = nodeMetadata(node, nodeType);
        metadata.put("evalMode", evaluation.mode().name());
        putIfPresent(metadata, "experimentId", evaluation.experimentId());
        putIfPresent(metadata, "itemId", evaluation.itemId());
        return new RuntimeGraphSpecExecutionResult(
                false, "EVAL_SIDE_EFFECT_BLOCKED", message, node.getId(), nodeType,
                List.of(step("eval-side-effect-blocked", node.getId())), metadata);
    }

    private Map<String, Object> nodeMetadata(GraphSpec.Node node, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", nodeType);
        return metadata;
    }

    private Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Use fallback.
            }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Use fallback.
            }
        }
        return fallback;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }
}
