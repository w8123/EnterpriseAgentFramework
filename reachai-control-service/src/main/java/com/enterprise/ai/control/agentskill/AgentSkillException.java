package com.enterprise.ai.control.agentskill;

import org.springframework.http.HttpStatus;

public class AgentSkillException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public AgentSkillException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public AgentSkillException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static AgentSkillException invalidPackage(String message) {
        return new AgentSkillException("SKILL_PACKAGE_INVALID", HttpStatus.BAD_REQUEST, message);
    }

    public static AgentSkillException conflict(String message) {
        return new AgentSkillException("SKILL_VERSION_CONFLICT", HttpStatus.CONFLICT, message);
    }

    public static AgentSkillException notFound(String message) {
        return new AgentSkillException("SKILL_NOT_FOUND", HttpStatus.NOT_FOUND, message);
    }

    public static AgentSkillException forbidden(String message) {
        return new AgentSkillException("SKILL_ACCESS_DENIED", HttpStatus.FORBIDDEN, message);
    }

    public static AgentSkillException invalidState(String message) {
        return new AgentSkillException("SKILL_VERSION_STATE_INVALID", HttpStatus.CONFLICT, message);
    }

    public static AgentSkillException artifactFailure(String message, Throwable cause) {
        return new AgentSkillException("SKILL_ARTIFACT_FAILURE", HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }
}
