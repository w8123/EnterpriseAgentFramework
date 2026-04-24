package com.enterprise.ai.skill;

/**
 * Human-in-the-loop 策略。Phase 2.0 仅占位，不真正触发审批流；
 * Phase 4.1 补审批台 + 挂起/恢复机制。
 */
public enum HitlPolicy {

    /** 永不审批，直接执行。 */
    NEVER,

    /** 副作用为 WRITE/IRREVERSIBLE 时要求审批。 */
    ON_WRITE,

    /** 每次调用都要求审批。 */
    ALWAYS
}
