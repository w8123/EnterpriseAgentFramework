package com.enterprise.ai.agent.tool.retrieval;

/**
 * 一次 Tool 检索的命中项：Tool 主键 + 名称 + 相似度分数 + 入库文本（用于调试）。
 */
public record ToolCandidate(
        Long toolId,
        String toolName,
        Long projectId,
        Long moduleId,
        float score,
        String text
) {
}
