package com.enterprise.ai.runtime.trace;

import java.util.List;
import java.util.stream.IntStream;

/** Narrow usage projection with the same whitespace definition as StringUtils.hasText. */
public final class RuntimeToolUsageSql {
    static final int PAGE_SIZE = 500;
    private static final List<String> WHITESPACE = IntStream.rangeClosed(0, Character.MAX_VALUE)
            .filter(value -> Character.isWhitespace((char) value))
            .mapToObj(value -> String.valueOf((char) value)).toList();

    private RuntimeToolUsageSql() { }

    static List<String> whitespaceCharacters() { return WHITESPACE; }

    public static String page() {
        String withoutWhitespace = "retrieval_trace_json";
        var whitespaceParameters = new java.util.StringJoiner(", ");
        for (int index = 0; index < WHITESPACE.size(); index++) {
            String parameter = "#{whitespace[" + index + "]}";
            whitespaceParameters.add(parameter);
            withoutWhitespace = "REPLACE(" + withoutWhitespace + ", " + parameter + ", '')";
        }
        // Ordinary JSON starts with a non-whitespace character. Only leading whitespace needs the full check.
        String retrieval = "CASE WHEN retrieval_trace_json IS NULL OR CHAR_LENGTH(retrieval_trace_json) = 0 THEN 0 "
                + "WHEN LEFT(retrieval_trace_json, 1) NOT IN (" + whitespaceParameters + ") THEN 1 "
                + "WHEN LENGTH(" + withoutWhitespace + ") > 0 THEN 1 ELSE 0 END";
        return "<script>SELECT id, create_time AS created_at, trace_id, intent_type, agent_name, "
                + retrieval + " AS retrieval FROM runtime_tool_call_log "
                + "WHERE create_time &gt;= #{from} AND id &lt;= #{ceiling} "
                + "<if test='afterTime != null'>AND (create_time &gt; #{afterTime} "
                + "OR (create_time = #{afterTime} AND id &gt; #{afterId})) </if>"
                + "ORDER BY create_time, id LIMIT " + PAGE_SIZE + "</script>";
    }
}
