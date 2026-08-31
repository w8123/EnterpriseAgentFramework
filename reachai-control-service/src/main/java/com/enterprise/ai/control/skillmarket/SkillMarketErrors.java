package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.agentskill.AgentSkillException;
import org.springframework.http.HttpStatus;

final class SkillMarketErrors {

    private SkillMarketErrors() {
    }

    static AgentSkillException invalid(String message) {
        return new AgentSkillException("SKILL_MARKET_REQUEST_INVALID", HttpStatus.BAD_REQUEST, message);
    }

    static AgentSkillException disabled() {
        return new AgentSkillException("SKILL_MARKET_DISABLED", HttpStatus.SERVICE_UNAVAILABLE,
                "Skill Market is disabled by platform configuration");
    }

    static AgentSkillException unsupportedSource(String message) {
        return new AgentSkillException("SKILL_MARKET_SOURCE_UNSUPPORTED", HttpStatus.BAD_REQUEST, message);
    }

    static AgentSkillException upstream(String message) {
        return new AgentSkillException("SKILL_MARKET_UPSTREAM_UNAVAILABLE", HttpStatus.BAD_GATEWAY, message);
    }

    static AgentSkillException upstream(String message, Throwable cause) {
        return new AgentSkillException("SKILL_MARKET_UPSTREAM_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                message, cause);
    }

    static AgentSkillException tooLarge(String message) {
        return new AgentSkillException("SKILL_MARKET_REMOTE_PACKAGE_TOO_LARGE",
                HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    static AgentSkillException changed(String message) {
        return new AgentSkillException("SKILL_MARKET_SOURCE_CHANGED", HttpStatus.CONFLICT, message);
    }
}
