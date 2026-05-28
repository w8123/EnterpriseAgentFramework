package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.platform.auth.PlatformAuthProviderConfigService;
import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform/auth-providers")
@RequiredArgsConstructor
public class PlatformAuthProviderController {

    private final PlatformAuthProviderConfigService providerConfigService;

    @GetMapping
    public ResponseEntity<ApiResult<List<PlatformAuthProviderConfigService.ProviderView>>> list() {
        return ResponseEntity.ok(ApiResult.ok(providerConfigService.listProviders()));
    }

    @PostMapping
    public ResponseEntity<ApiResult<PlatformAuthProviderConfigService.ProviderView>> save(
            @RequestBody PlatformAuthProviderConfigService.ProviderCommand command) {
        return ResponseEntity.ok(ApiResult.ok(providerConfigService.saveProvider(command)));
    }
}
