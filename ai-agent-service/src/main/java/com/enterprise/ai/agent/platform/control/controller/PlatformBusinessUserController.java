package com.enterprise.ai.agent.platform.control.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.platform.control.identity.BusinessUserDirectoryService;
import com.enterprise.ai.agent.platform.control.identity.BusinessUserEntity;
import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform/business-users")
@RequiredArgsConstructor
public class PlatformBusinessUserController {

    private final BusinessUserDirectoryService directoryService;

    @GetMapping
    public ResponseEntity<ApiResult<Page<BusinessUserDirectoryService.BusinessUserView>>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResult.ok(
                directoryService.pageDirectoryUsers(current, size, tenantId, keyword, status)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<BusinessUserEntity>> update(@PathVariable Long id,
                                                                @RequestBody BusinessUserDirectoryService.BusinessUserUpdateCommand command) {
        return ResponseEntity.ok(ApiResult.ok(directoryService.updateBusinessUser(id, command)));
    }

    @GetMapping("/{id}/identities")
    public ResponseEntity<ApiResult<List<BusinessUserDirectoryService.ExternalIdentityView>>> identities(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResult.ok(directoryService.listExternalIdentities(id)));
    }

    @PostMapping("/{id}/identities")
    public ResponseEntity<ApiResult<BusinessUserDirectoryService.ExternalIdentityView>> saveIdentity(
            @PathVariable Long id,
            @RequestBody BusinessUserDirectoryService.ExternalIdentityCommand command) {
        return ResponseEntity.ok(ApiResult.ok(directoryService.saveExternalIdentity(id, command)));
    }
}
