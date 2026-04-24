package com.enterprise.ai.agent.acl;

/**
 * 单次 ACL 决策的结果（只用于日志 / 审计 / 单元测试诊断）。
 *
 * <p>结果语义：
 * <ul>
 *   <li>{@link #ALLOW}：允许调用；</li>
 *   <li>{@link #DENY_EXPLICIT}：命中了一条 {@code DENY} 规则（DENY 在决策里优先级最高）；</li>
 *   <li>{@link #DENY_NO_MATCH}：没有任何 ALLOW / DENY 命中，保守拒绝；</li>
 *   <li>{@link #SKIPPED}：上下文 {@code roles} 为空，走兼容旧行为，不做拦截。</li>
 * </ul>
 */
public enum ToolAclDecision {
    ALLOW,
    DENY_EXPLICIT,
    DENY_NO_MATCH,
    SKIPPED;

    public boolean isDenied() {
        return this == DENY_EXPLICIT || this == DENY_NO_MATCH;
    }
}
