package com.enterprise.ai.agent.scan;

import java.util.List;

/**
 * 扫描项目「删除 / 非增量重新扫描」前的外部引用检测结果。
 * <p>
 * 全局 {@code tool_definition} 中 {@code project_id} 指向本扫描项目的 Tool / Skill，
 * 若仍被某 Agent 的 tools / skills 白名单引用，则不允许删除或全量重扫（避免破坏线上 Agent）。
 */
public record ScanProjectBlockers(
        boolean blocked,
        List<String> toolNames,
        List<String> skillNames,
        List<AgentRef> agents
) {
    public static ScanProjectBlockers empty() {
        return new ScanProjectBlockers(false, List.of(), List.of(), List.of());
    }

    public record AgentRef(String id, String name) {
    }
}
