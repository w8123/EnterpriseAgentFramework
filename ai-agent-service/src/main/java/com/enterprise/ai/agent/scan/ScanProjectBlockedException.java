package com.enterprise.ai.agent.scan;

/**
 * 扫描项目删除或重新扫描被引用拦截时抛出，携带结构化列表供 API 返回。
 */
public class ScanProjectBlockedException extends RuntimeException {

    private final ScanProjectBlockers blockers;

    public ScanProjectBlockedException(ScanProjectBlockers blockers) {
        super("扫描项目仍被 Agent 引用全局 Tool/Skill，请先解除引用后再操作");
        this.blockers = blockers;
    }

    public ScanProjectBlockers getBlockers() {
        return blockers;
    }
}
