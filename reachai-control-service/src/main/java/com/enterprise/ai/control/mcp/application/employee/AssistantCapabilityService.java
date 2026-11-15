package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService.EmployeeAuditRequest;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService.EmployeeCallResult;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService.EmployeeSession;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService.SystemSession;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpToolCallResult;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the assistant embedded in a business system can do for the employee chatting with it: every
 * capability that system publishes to employees, found by need and called as that employee. Nothing
 * here widens access: the tools, call permission, write approval and audit are the employee's own.
 */
@Service
@RequiredArgsConstructor
public class AssistantCapabilityService {

    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 8;
    static final int CATALOG_LIMIT = 60;
    private static final int DESCRIPTION_LIMIT = 400;
    private static final int AUDIT_JSON_LIMIT = 16_000;
    static final String UNAVAILABLE_MESSAGE =
            "这个业务系统还没有向员工开放能力，或者当前账号还没有同步到 ReachAI。请管理员在「MCP 互联中心」发布该系统的能力并打开「员工使用」。";

    private final McpEmployeeProtocolService employees;
    private final ObjectMapper objectMapper;

    /** The few capabilities that best match what the employee asked for, with the schema needed to call them. */
    public SearchResult search(Caller caller, String query, Integer limit) {
        Optional<EmployeeSession> session = session(caller);
        if (session.isEmpty()) {
            return new SearchResult(false, UNAVAILABLE_MESSAGE, 0, List.of());
        }
        List<Map<String, Object>> visible = employees.visibleTools(session.get()).stream()
                .filter(tool -> !EmployeeMemoryTools.handles(String.valueOf(tool.get("name"))))
                .filter(tool -> !caller.readOnly() || readOnly(tool))
                .toList();
        int size = Math.max(1, Math.min(MAX_LIMIT, limit == null ? DEFAULT_LIMIT : limit));
        Set<String> terms = terms(query);
        record Scored(Map<String, Object> tool, int score, int order) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int index = 0; index < visible.size(); index++) {
            Map<String, Object> tool = visible.get(index);
            int score = terms.isEmpty() ? 1 : score(tool, terms);
            if (score > 0) {
                scored.add(new Scored(tool, score, index));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order));
        List<Map<String, Object>> matches = scored.stream().limit(size).map(item -> {
            Map<String, Object> view = view(item.tool());
            // Where the capability comes from, so repeated reasoning to it can later become a fixed flow.
            var exposed = session.get().exposed().get(String.valueOf(item.tool().get("name")));
            if (exposed != null) {
                view.put("sourceKind", exposed.tool().sourceKind().name());
                view.put("sourceRef", exposed.tool().sourceRef());
            }
            return view;
        }).toList();
        String message = matches.isEmpty()
                ? "没有找到匹配的能力。换一个业务词再查一次（例如 客户、订单、库存）；仍然没有就告诉用户这个系统目前没有开放这项能力。"
                : null;
        return new SearchResult(true, message, visible.size(), matches);
    }

    /**
     * Every read-only capability the account may call, with name, description and schema, for writing
     * evaluation questions. Nothing is called.
     */
    public SearchResult readCatalog(Caller caller, Integer limit) {
        Optional<EmployeeSession> session = session(caller);
        if (session.isEmpty()) {
            // An admin picked this account, so say exactly what is missing instead of the employee wording.
            String problem = caller == null ? null
                    : employees.assistantSessionProblem(caller.tenantId(), caller.projectCode(), caller.externalUserId());
            return new SearchResult(false, problem == null ? UNAVAILABLE_MESSAGE : problem, 0, List.of());
        }
        List<Map<String, Object>> readable = employees.visibleTools(session.get()).stream()
                .filter(tool -> !EmployeeMemoryTools.handles(String.valueOf(tool.get("name"))))
                .filter(AssistantCapabilityService::readOnly)
                // ReachAI's own helpers (approval status and the like) are not the business system's.
                .filter(tool -> !String.valueOf(tool.get("name")).startsWith("reachai_"))
                .toList();
        int size = Math.max(1, Math.min(CATALOG_LIMIT, limit == null ? CATALOG_LIMIT : limit));
        return new SearchResult(true, null, readable.size(), readable.stream().limit(size)
                .map(AssistantCapabilityService::view).toList());
    }

    /** Calls one capability as the employee. A write that needs approval comes back as a pending approval, not a result. */
    public CallResult call(Caller caller, String toolName, Map<String, Object> arguments) {
        Optional<EmployeeSession> session = session(caller);
        if (session.isEmpty()) {
            return CallResult.refused("ASSISTANT_CAPABILITIES_UNAVAILABLE", UNAVAILABLE_MESSAGE);
        }
        String name = toolName == null ? "" : toolName.trim();
        if (name.isEmpty() || EmployeeMemoryTools.handles(name)) {
            return CallResult.refused("MCP_TOOL_NOT_VISIBLE", "没有这个能力：" + name);
        }
        if (caller.readOnly() && employees.visibleTools(session.get()).stream()
                .noneMatch(tool -> name.equals(String.valueOf(tool.get("name"))) && readOnly(tool))) {
            // Automatic evaluation never writes, whatever the evaluation account itself may do.
            return CallResult.refused("CAPABILITY_READ_ONLY", "自动评测只能调用只读能力：" + name);
        }
        Map<String, Object> exactArguments = arguments == null ? Map.of() : arguments;
        long startedAt = System.nanoTime();
        SystemSession system = session.get().exposed().get(name) == null
                ? null : session.get().exposed().get(name).system();
        try {
            EmployeeCallResult called = employees.callTool(session.get(), name, exactArguments);
            McpToolCallResult result = called.result();
            boolean approvalPending = result.success() && result.output() != null
                    && result.output().get("approvalId") != null
                    && "PENDING".equals(String.valueOf(result.output().get("approvalStatus")));
            audit(session.get(), called.system() == null ? system : called.system(), name, exactArguments,
                    result.success(), result.success() ? null : "RUNTIME", result.output(), result.errorCode(),
                    result.traceId(), result.runId(), startedAt);
            return new CallResult(result.success(), approvalPending,
                    result.output() == null ? Map.of() : result.output(), result.errorCode(), null,
                    result.runId(), result.traceId());
        } catch (McpDomainException refused) {
            audit(session.get(), system, name, exactArguments, false, "POLICY", Map.of(), refused.code(),
                    null, null, startedAt);
            return CallResult.refused(refused.code(), refused.getMessage());
        }
    }

    private Optional<EmployeeSession> session(Caller caller) {
        if (caller == null) {
            return Optional.empty();
        }
        return employees.assistantSession(caller.tenantId(), caller.projectCode(), caller.externalUserId());
    }

    private void audit(EmployeeSession session, SystemSession system, String toolName, Map<String, Object> arguments,
                       boolean success, String errorCategory, Map<String, Object> output, String errorCode,
                       String traceId, String runId, long startedAt) {
        employees.audit(new EmployeeAuditRequest(session, system, "assistant/tools/call", toolName, success,
                (System.nanoTime() - startedAt) / 1_000_000L, errorCategory,
                bounded(json(Map.of("arguments", arguments)), AUDIT_JSON_LIMIT),
                bounded(json(output == null ? Map.of() : output), AUDIT_JSON_LIMIT),
                errorCode, traceId, runId, null));
    }

    /** A capability Control published as READ; anything unmarked counts as a write. */
    static boolean readOnly(Map<String, Object> tool) {
        return tool.get("_meta") instanceof Map<?, ?> meta
                && "READ".equalsIgnoreCase(String.valueOf(meta.get("reachai/riskLevel")));
    }

    private static Map<String, Object> view(Map<String, Object> tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.get("name"));
        view.put("description", bounded(String.valueOf(tool.getOrDefault("description", "")), DESCRIPTION_LIMIT));
        Object meta = tool.get("_meta");
        if (meta instanceof Map<?, ?> values) {
            view.put("riskLevel", values.get("reachai/riskLevel"));
            view.put("requiresApproval", Boolean.TRUE.equals(values.get("reachai/requiresApproval")));
        }
        view.put("inputSchema", tool.get("inputSchema"));
        return view;
    }

    private int score(Map<String, Object> tool, Set<String> terms) {
        String name = String.valueOf(tool.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
        String description = String.valueOf(tool.getOrDefault("description", "")).toLowerCase(Locale.ROOT);
        String schema = json(tool.get("inputSchema")).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (name.contains(term)) score += 3;
            if (description.contains(term)) score += 2;
            if (schema.contains(term)) score += 1;
        }
        return score;
    }

    /** Latin words as they are; a run of CJK characters as itself plus its two-character pieces. */
    static Set<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        String text = query.toLowerCase(Locale.ROOT) + " ";
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean han = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
            boolean word = !han && (Character.isLetterOrDigit(codePoint));
            if (!word && latin.length() > 0) {
                if (latin.length() > 1) terms.add(latin.toString());
                latin.setLength(0);
            }
            if (!han && cjk.length() > 0) {
                String run = cjk.toString();
                if (run.length() <= 6) terms.add(run);
                for (int index = 0; index + 2 <= run.length(); index++) {
                    terms.add(run.substring(index, index + 2));
                }
                if (run.length() == 1) terms.add(run);
                cjk.setLength(0);
            }
            if (han) cjk.appendCodePoint(codePoint);
            else if (word) latin.appendCodePoint(codePoint);
        }
        return terms;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            return "";
        }
    }

    private static String bounded(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    /** Who the assistant acts for; every field comes from the verified chat session, never from the model. */
    public record Caller(String tenantId, String projectCode, String externalUserId, boolean readOnly) {
        public Caller(String tenantId, String projectCode, String externalUserId) {
            this(tenantId, projectCode, externalUserId, false);
        }
    }

    public record SearchResult(boolean available, String message, int total, List<Map<String, Object>> tools) {
    }

    public record CallResult(boolean success, boolean approvalPending, Map<String, Object> output,
                             String errorCode, String message, String runId, String traceId) {
        static CallResult refused(String code, String message) {
            return new CallResult(false, false, Map.of(), code, message, null, null);
        }
    }
}
