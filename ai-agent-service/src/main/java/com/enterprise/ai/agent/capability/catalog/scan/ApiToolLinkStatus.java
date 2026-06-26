package com.enterprise.ai.agent.capability.catalog.scan;

/**
 * 项目 API 目录行与全局 {@code tool_definition} 的关联健康状态（统一扫描与 SDK 镜像行）。
 */
public enum ApiToolLinkStatus {
    /** 尚未「添加为 Tool」 */
    NOT_LINKED,
    /** 已关联且可同步字段一致 */
    IN_SYNC,
    /** 扫描行与全局 Tool 可同步字段不一致，需「更新到 Tool」 */
    PENDING_UPDATE,
    /** 接口在 SDK/扫描源中已移除（墓碑行），全局 Tool 可能仍存在 */
    API_REMOVED_STALE,
    /** 扫描行仍记录关联 ID，但全局 Tool 记录已不存在 */
    GLOBAL_MISSING
}
