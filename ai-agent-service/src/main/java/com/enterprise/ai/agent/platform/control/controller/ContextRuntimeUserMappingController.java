package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserMappingCreateRequest;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserMappingQueryRequest;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserMappingResponse;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserMappingService;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthContext;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/context/runtime-user-mappings")
@RequiredArgsConstructor
public class ContextRuntimeUserMappingController {

    private static final String MANAGE_PERMISSION = "context:runtime-user:mapping:manage";

    private final ContextRuntimeUserMappingService mappingService;

    @GetMapping
    public ApiResult<List<ContextRuntimeUserMappingResponse>> listMappings(ContextRuntimeUserMappingQueryRequest query) {
        try {
            requireManagePrincipal();
            return ApiResult.ok(mappingService.listMappings(query));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @PostMapping
    public ApiResult<ContextRuntimeUserMappingResponse> createMapping(
            @RequestBody ContextRuntimeUserMappingCreateRequest request) {
        try {
            PlatformPrincipal principal = requireManagePrincipal();
            if (request == null) {
                throw new IllegalArgumentException("request is required");
            }
            if (!StringUtils.hasText(request.getCreatedBy())) {
                request.setCreatedBy(String.valueOf(principal.userId()));
            }
            return ApiResult.ok(mappingService.createMapping(request));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 400, ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResult<ContextRuntimeUserMappingResponse> deleteMapping(@PathVariable Long id) {
        try {
            PlatformPrincipal principal = requireManagePrincipal();
            return ApiResult.ok(mappingService.deleteMapping(id, String.valueOf(principal.userId())));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(accessDenied(ex) ? 403 : 404, ex.getMessage());
        }
    }

    private PlatformPrincipal requireManagePrincipal() {
        PlatformPrincipal principal = PlatformAuthContext.get();
        if (principal == null || principal.userId() == null) {
            throw new IllegalArgumentException("platform login required");
        }
        if (principal.permissions() == null
                || (!principal.permissions().contains("*")
                && !principal.permissions().contains("platform:admin")
                && !principal.permissions().contains(MANAGE_PERMISSION))) {
            throw new IllegalArgumentException("Runtime user mapping access denied");
        }
        return principal;
    }

    private boolean accessDenied(IllegalArgumentException ex) {
        String message = ex.getMessage();
        return message != null && (message.contains("access denied") || message.contains("login required"));
    }
}
