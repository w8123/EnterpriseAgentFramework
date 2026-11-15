package com.enterprise.ai.control.platform.sso;

import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.enterprise.ai.control.platform.PlatformPublicOrigin;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Public half of 「用业务系统账号登录」: login-page buttons, the redirect out, and the callback. */
@RestController
@RequiredArgsConstructor
public class BusinessSsoController {

    private final BusinessSsoService service;
    private final McpHubProperties properties;

    @GetMapping("/api/platform/auth/sso/providers")
    public ResponseEntity<List<BusinessSsoService.LoginButton>> providers() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.buttons());
    }

    @GetMapping("/api/platform/auth/sso/{code}/start")
    public ResponseEntity<Void> start(@PathVariable String code, HttpServletRequest request) {
        String location;
        try {
            location = service.start(code, PlatformPublicOrigin.resolve(properties, request));
        } catch (BusinessSsoException failure) {
            location = "/login?sso_error=" + URLEncoder.encode(failure.getMessage(), StandardCharsets.UTF_8);
        }
        return redirect(location, List.of());
    }

    @GetMapping("/api/platform/auth/sso/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request) {
        BusinessSsoService.Outcome outcome = service.callback(code, state, error, request);
        return redirect(outcome.location(), outcome.cookies());
    }

    /** 账号密码验证: answers JSON so the login page can show the reason in place. */
    @org.springframework.web.bind.annotation.PostMapping("/api/platform/auth/sso/{code}/password")
    public ResponseEntity<java.util.Map<String, Object>> password(
            @PathVariable String code,
            @org.springframework.web.bind.annotation.RequestBody(required = false) PasswordLoginRequest body,
            HttpServletRequest request) {
        BusinessSsoService.PasswordOutcome outcome = service.passwordLogin(code,
                body == null ? null : body.username(), body == null ? null : body.password(), request);
        // Always 200: a refusal is an answer the login page shows, not a lost console session.
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer");
        if (!outcome.cookies().isEmpty()) response.header(HttpHeaders.SET_COOKIE, outcome.cookies().toArray(new String[0]));
        return response.body(outcome.error() == null
                ? java.util.Map.of("location", outcome.location())
                : java.util.Map.of("message", outcome.error()));
    }

    public record PasswordLoginRequest(String username, String password) {
        @Override
        public String toString() {
            return "PasswordLoginRequest[username=" + username + "]";
        }
    }

    private static ResponseEntity<Void> redirect(String location, List<String> cookies) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer");
        if (!cookies.isEmpty()) response.header(HttpHeaders.SET_COOKIE, cookies.toArray(new String[0]));
        return response.build();
    }
}
