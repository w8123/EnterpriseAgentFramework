package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.auth.PlatformAuthContext;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthService;
import com.enterprise.ai.agent.platform.control.auth.PlatformLoginRequest;
import com.enterprise.ai.agent.platform.control.auth.PlatformLoginResult;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import com.enterprise.ai.common.dto.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformAuthController {

    private final PlatformAuthService authService;

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResult<PlatformLoginResult>> login(@RequestBody LoginRequest request,
                                                                HttpServletRequest servletRequest) {
        PlatformLoginResult result = authService.login(PlatformLoginRequest.builder()
                .username(request.username())
                .password(request.password())
                .providerCode(request.providerCode())
                .providerType(request.providerType())
                .idToken(request.idToken())
                .samlResponse(request.samlResponse())
                .ip(servletRequest.getRemoteAddr())
                .userAgent(servletRequest.getHeader("User-Agent"))
                .headers(headers(servletRequest))
                .build());
        return ResponseEntity.ok(ApiResult.ok(result));
    }

    @GetMapping("/auth/me")
    public ResponseEntity<ApiResult<PlatformPrincipal>> me() {
        return ResponseEntity.ok(ApiResult.ok(PlatformAuthContext.get()));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResult<Void>> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : null;
        authService.logout(token);
        return ResponseEntity.ok(ApiResult.ok());
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResult<List<UserView>>> users() {
        return ResponseEntity.ok(ApiResult.ok(authService.listUsers().stream()
                .map(user -> new UserView(user.getId(), user.getUsername(), user.getDisplayName(),
                        user.getStatus(), user.getSourceProvider(), user.getLastLoginAt()))
                .toList()));
    }

    @GetMapping("/users/{userId}/roles")
    public ResponseEntity<ApiResult<List<PlatformAuthService.UserRoleGrantView>>> userRoles(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResult.ok(authService.listUserRoleGrants(userId)));
    }

    @PutMapping("/users/{userId}/roles")
    public ResponseEntity<ApiResult<List<PlatformAuthService.UserRoleGrantView>>> saveUserRoles(
            @PathVariable Long userId,
            @RequestBody List<PlatformAuthService.UserRoleGrantCommand> request) {
        return ResponseEntity.ok(ApiResult.ok(authService.replaceUserRoleGrants(userId, request)));
    }

    @GetMapping("/roles")
    public ResponseEntity<ApiResult<List<RoleView>>> roles() {
        return ResponseEntity.ok(ApiResult.ok(authService.listRoles().stream()
                .map(role -> new RoleView(role.getId(), role.getRoleCode(), role.getRoleName(), role.getStatus()))
                .toList()));
    }

    private Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    public record LoginRequest(String username, String password, String providerCode, String providerType, String idToken, String samlResponse) {
    }

    public record UserView(Long id, String username, String displayName, String status,
                           String sourceProvider, java.time.LocalDateTime lastLoginAt) {
    }

    public record RoleView(Long id, String roleCode, String roleName, String status) {
    }
}
