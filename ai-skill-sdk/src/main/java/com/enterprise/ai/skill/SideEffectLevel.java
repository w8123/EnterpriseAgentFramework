package com.enterprise.ai.skill;

/**
 * Tool / Skill 的副作用等级。扫描期按 HTTP method + 命名线索推断默认值，管理端可 override。
 * <p>
 * Phase 2.0 仅作为语义标注写入 prompt；真正的 HITL 执行门禁留给 Phase 4.1。
 */
public enum SideEffectLevel {

    /** 无副作用（纯内部计算）。 */
    NONE,

    /** 只读（GET 查询类）。 */
    READ_ONLY,

    /** 幂等写入（带 idempotency-key 的写入）。 */
    IDEMPOTENT_WRITE,

    /** 普通写入（POST/PUT/PATCH）。 */
    WRITE,

    /** 不可逆操作（DELETE、扣款等）。 */
    IRREVERSIBLE
}
