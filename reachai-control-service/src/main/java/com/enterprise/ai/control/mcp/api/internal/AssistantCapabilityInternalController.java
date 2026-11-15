package com.enterprise.ai.control.mcp.api.internal;

import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.enterprise.ai.control.mcp.application.employee.AssistantCapabilityService;
import com.enterprise.ai.control.mcp.application.employee.AssistantCapabilityService.CallResult;
import com.enterprise.ai.control.mcp.application.employee.AssistantCapabilityService.Caller;
import com.enterprise.ai.control.mcp.application.employee.AssistantCapabilityService.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Runtime-facing boundary for the embedded assistant's capability toolbox. Only a signed Runtime
 * request is accepted, and the employee it names is the one Runtime verified for the chat session.
 */
@RestController
@RequestMapping("/internal/control/assistant-capabilities")
@RequiredArgsConstructor
public class AssistantCapabilityInternalController {

    private final AssistantCapabilityService capabilities;
    private final ControlInternalServiceAuthVerifier authVerifier;
    private final ObjectMapper objectMapper;

    @PostMapping("/search")
    public SearchResult search(HttpServletRequest request, @RequestBody byte[] exactBody) {
        authVerifier.requireRuntime(request, exactBody);
        SearchRequest body = read(exactBody, SearchRequest.class);
        return capabilities.search(caller(body.tenantId(), body.projectCode(), body.externalUserId(), body.readOnly()),
                body.query(), body.limit());
    }

    /** Every read-only capability the account may call: what automatic evaluation writes its questions from. */
    @PostMapping("/read-catalog")
    public SearchResult readCatalog(HttpServletRequest request, @RequestBody byte[] exactBody) {
        authVerifier.requireRuntime(request, exactBody);
        SearchRequest body = read(exactBody, SearchRequest.class);
        return capabilities.readCatalog(caller(body.tenantId(), body.projectCode(), body.externalUserId(), true),
                body.limit());
    }

    @PostMapping("/call")
    public CallResult call(HttpServletRequest request, @RequestBody byte[] exactBody) {
        authVerifier.requireRuntime(request, exactBody);
        CallRequest body = read(exactBody, CallRequest.class);
        return capabilities.call(caller(body.tenantId(), body.projectCode(), body.externalUserId(), body.readOnly()),
                body.toolName(), body.arguments());
    }

    private static Caller caller(String tenantId, String projectCode, String externalUserId, Boolean readOnly) {
        if (projectCode == null || projectCode.isBlank() || externalUserId == null || externalUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectCode and externalUserId are required");
        }
        return new Caller(tenantId, projectCode.trim(), externalUserId.trim(), Boolean.TRUE.equals(readOnly));
    }

    private <T> T read(byte[] exactBody, Class<T> type) {
        try {
            T body = objectMapper.readValue(exactBody, type);
            if (body == null) {
                throw new IllegalArgumentException("empty body");
            }
            return body;
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assistant capability request body is invalid");
        }
    }

    /** {@code readOnly=true}: automatic evaluation, which may only see and run READ capabilities. */
    public record SearchRequest(String tenantId, String projectCode, String externalUserId, String query, Integer limit,
                                Boolean readOnly) {
    }

    public record CallRequest(String tenantId, String projectCode, String externalUserId, String toolName,
                              Map<String, Object> arguments, Boolean readOnly) {
    }
}
