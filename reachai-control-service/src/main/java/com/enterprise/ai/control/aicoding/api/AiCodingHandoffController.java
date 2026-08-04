package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.application.AiCodingHandoffApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ActivationView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        value = "/api/ai-coding/handoffs",
        produces = AiCodingRequestSupport.JSON_UTF8_VALUE)
@RequiredArgsConstructor
public class AiCodingHandoffController {

    private final AiCodingHandoffApplicationService handoffService;

    @PostMapping("/{handoffId}/activate")
    public ResponseEntity<ActivationView> activate(
            @PathVariable String handoffId,
            @RequestBody ActivationCommand command,
            HttpServletRequest request) {
        return ResponseEntity.ok(handoffService.activate(
                handoffId,
                command,
                AiCodingRequestSupport.publicBaseUrl(request)));
    }
}
