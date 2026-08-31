package com.enterprise.ai.control.agentskill;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

@RestControllerAdvice(assignableTypes = {
        AgentSkillController.class,
        com.enterprise.ai.control.skillmarket.SkillMarketController.class,
        com.enterprise.ai.control.aiassist.ControlAiAssistSkillController.class,
        com.enterprise.ai.control.runtime.ControlRuntimePublicController.class,
        com.enterprise.ai.control.internal.InternalAgentSkillCatalogController.class
})
public class AgentSkillExceptionHandler {

    @ExceptionHandler(AgentSkillException.class)
    public ResponseEntity<ErrorResponse> handle(AgentSkillException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ErrorResponse(exception.code(), exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handle(MaxUploadSizeExceededException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("SKILL_PACKAGE_TOO_LARGE",
                        "Skill ZIP exceeds the configured upload limit", Instant.now()));
    }

    public record ErrorResponse(String code, String message, Instant timestamp) {
    }
}
