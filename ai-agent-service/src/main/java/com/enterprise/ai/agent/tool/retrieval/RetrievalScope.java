package com.enterprise.ai.agent.tool.retrieval;

import java.util.List;
import java.util.Set;

/**
 * Tool 检索范围过滤条件。
 * <p>
 * 所有字段都是「可选」：空列表 / null 表示不约束该维度。
 * <p>
 * 典型用法：
 * <ul>
 *   <li>Agent 调用前：按 {@code AgentDefinition.tools} 做 whitelist + {@code enabledOnly/agentVisibleOnly}。</li>
 *   <li>管理端检索测试：按 projectIds / moduleIds / kinds 精准定位。</li>
 * </ul>
 *
 * @param projectIds       限定扫描项目 ID（空则不限）
 * @param moduleIds        限定扫描模块 ID（空则不限）
 * @param toolWhitelist    tool_definition.id 白名单（空则不限，等价于全库召回）
 * @param enabledOnly      仅启用的 tool
 * @param agentVisibleOnly 仅 agent 可见的 tool
 * @param kinds            仅召回这些 kind（TOOL/SKILL）；null 或空 = 不限。Phase 2.0 新增。
 * @param domains          仅召回挂接到这些 domain 的 tool / skill；空 = 不限。Phase P1 新增。
 *                         本字段是软过滤维度：若过滤后召回为空，调用方可回退不带 domain 的查询。
 */
public record RetrievalScope(
        List<Long> projectIds,
        List<Long> moduleIds,
        List<Long> toolWhitelist,
        boolean enabledOnly,
        boolean agentVisibleOnly,
        Set<String> kinds,
        Set<String> domains
) {

    /** 兼容旧构造：不限 kind / domain。 */
    public RetrievalScope(List<Long> projectIds,
                          List<Long> moduleIds,
                          List<Long> toolWhitelist,
                          boolean enabledOnly,
                          boolean agentVisibleOnly) {
        this(projectIds, moduleIds, toolWhitelist, enabledOnly, agentVisibleOnly, null, null);
    }

    /** 兼容旧构造：不限 domain。 */
    public RetrievalScope(List<Long> projectIds,
                          List<Long> moduleIds,
                          List<Long> toolWhitelist,
                          boolean enabledOnly,
                          boolean agentVisibleOnly,
                          Set<String> kinds) {
        this(projectIds, moduleIds, toolWhitelist, enabledOnly, agentVisibleOnly, kinds, null);
    }

    public static RetrievalScope agentRuntime(List<Long> whitelist) {
        return new RetrievalScope(null, null, whitelist, true, true, null, null);
    }

    public static RetrievalScope allEnabled() {
        return new RetrievalScope(null, null, null, true, true, null, null);
    }

    public static RetrievalScope none() {
        return new RetrievalScope(null, null, null, false, false, null, null);
    }

    /** 仅限工具。 */
    public static RetrievalScope onlyTools() {
        return new RetrievalScope(null, null, null, true, true, Set.of("TOOL"), null);
    }

    /** 仅限 Skill。 */
    public static RetrievalScope onlySkills() {
        return new RetrievalScope(null, null, null, true, true, Set.of("SKILL"), null);
    }

    /** 在当前 scope 上额外加 domain 过滤（不可变 record，返回新实例）。 */
    public RetrievalScope withDomains(Set<String> domainCodes) {
        return new RetrievalScope(projectIds, moduleIds, toolWhitelist, enabledOnly,
                agentVisibleOnly, kinds, domainCodes);
    }
}
