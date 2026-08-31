package com.enterprise.ai.model.catalog;

import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/model/catalog")
@RequiredArgsConstructor
public class ModelCatalogController {

    private final ModelCatalogStatusService statusService;
    private final ModelCatalogSettingsService settingsService;
    private final ModelCatalogManualSyncService manualSyncService;

    @GetMapping("/status")
    public ApiResult<ModelCatalogStatusResponse> status() {
        return ApiResult.ok(statusService.status());
    }

    @PutMapping("/settings")
    public ApiResult<ModelCatalogStatusResponse> updateSettings(@RequestBody ModelCatalogSettingsRequest request) {
        settingsService.updateAutoSyncEnabled(request == null ? null : request.autoSyncEnabled());
        return ApiResult.ok(statusService.status());
    }

    @PostMapping("/sync")
    public ApiResult<ModelCatalogManualSyncResponse> synchronizeNow() {
        return ApiResult.ok(manualSyncService.trigger());
    }
}
