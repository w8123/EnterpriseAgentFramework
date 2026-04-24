package com.enterprise.ai.agent.tool.retrieval;

import java.util.List;

/**
 * Tool 检索范围过滤条件。
 * <p>
 * 所有字段都是「可选」：空列表 / null 表示不约束该维度。
 * <p>
 * 典型用法：
 * <ul>
 *   <li>Agent 调用前：按 {@code AgentDefinition.tools} 做 whitelist + {@code enabledOnly/agentVisibleOnly}。</li>
 *   <li>管理端检索测试：按 projectIds / moduleIds 精准定位。</li>
 * </ul>
 *
 * @param projectIds       限定扫描项目 ID（空则不限）
 * @param moduleIds        限定扫描模块 ID（空则不限）
 * @param toolWhitelist    tool_definition.id 白名单（空则不限，等价于全库召回）
 * @param enabledOnly      仅启用的 tool
 * @param agentVisibleOnly 仅 agent 可见的 tool
 */
public record RetrievalScope(
        List<Long> projectIds,
        List<Long> moduleIds,
        List<Long> toolWhitelist,
        boolean enabledOnly,
        boolean agentVisibleOnly
) {

    public static RetrievalScope agentRuntime(List<Long> whitelist) {
        return new RetrievalScope(null, null, whitelist, true, true);
    }

    public static RetrievalScope allEnabled() {
        return new RetrievalScope(null, null, null, true, true);
    }

    public static RetrievalScope none() {
        return new RetrievalScope(null, null, null, false, false);
    }
}
