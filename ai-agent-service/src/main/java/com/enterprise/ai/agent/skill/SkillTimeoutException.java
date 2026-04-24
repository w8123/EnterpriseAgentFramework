package com.enterprise.ai.agent.skill;

/**
 * Skill 执行超时异常，保留 skillName 和 timeoutMs 便于审计与前端展示。
 */
public class SkillTimeoutException extends RuntimeException {

    private final String skillName;
    private final long timeoutMs;

    public SkillTimeoutException(String skillName, long timeoutMs, Throwable cause) {
        super("Skill 执行超时: skill=" + skillName + ", timeoutMs=" + timeoutMs, cause);
        this.skillName = skillName;
        this.timeoutMs = timeoutMs;
    }

    public String getSkillName() {
        return skillName;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
