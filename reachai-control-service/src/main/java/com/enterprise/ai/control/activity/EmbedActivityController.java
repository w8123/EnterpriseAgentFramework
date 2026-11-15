package com.enterprise.ai.control.activity;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.platform.PlatformEmbedTokenClaims;
import com.enterprise.ai.control.platform.PlatformEmbedTokenException;
import com.enterprise.ai.control.platform.PlatformEmbedTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 快捷操作 shown inside the business system's embedded chat, for the page the employee is on. */
@RestController
@RequestMapping("/api/embed/activity")
public class EmbedActivityController {

    private final PlatformEmbedTokenService tokenService;
    private final ActivityInsightService insightService;

    public EmbedActivityController(PlatformEmbedTokenService tokenService, ActivityInsightService insightService) {
        this.tokenService = tokenService;
        this.insightService = insightService;
    }

    @GetMapping("/shortcuts")
    public ResponseEntity<ApiResult<ActivityInsightService.Shortcuts>> shortcuts(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String route) {
        String project;
        try {
            project = project(authorization);
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401, ex.getMessage()));
        }
        return ResponseEntity.ok(ApiResult.ok(insightService.shortcuts(project, route)));
    }

    @PostMapping("/shortcuts/{id}/used")
    public ResponseEntity<ApiResult<Map<String, Object>>> used(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id) {
        String project;
        try {
            project = project(authorization);
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401, ex.getMessage()));
        }
        return ResponseEntity.ok(ApiResult.ok(Map.of("counted", insightService.markUsed(project, id))));
    }

    String project(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new PlatformEmbedTokenException("Authorization Bearer embed token is required");
        }
        PlatformEmbedTokenClaims claims = tokenService.verify(authorization.substring("Bearer ".length()).trim());
        String project = StringUtils.hasText(claims.getProjectCode()) ? claims.getProjectCode().trim() : claims.getAppId();
        if (!StringUtils.hasText(project)) throw new PlatformEmbedTokenException("embed token has no business system");
        return project.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
