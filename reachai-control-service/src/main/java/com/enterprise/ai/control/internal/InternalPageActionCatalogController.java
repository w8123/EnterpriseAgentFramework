package com.enterprise.ai.control.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.control.platform.PlatformPageActionRegistryEntity;
import com.enterprise.ai.control.platform.PlatformPageActionRegistryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/control/page-actions")
@RequiredArgsConstructor
public class InternalPageActionCatalogController {

    private final PlatformPageActionRegistryMapper mapper;

    @GetMapping("/{projectCode}/{pageKey}/{actionKey}")
    public ResponseEntity<PageActionCatalogEntry> getPageAction(@PathVariable String projectCode,
                                                                @PathVariable String pageKey,
                                                                @PathVariable String actionKey) {
        PlatformPageActionRegistryEntity row = mapper.selectOne(new LambdaQueryWrapper<PlatformPageActionRegistryEntity>()
                .eq(PlatformPageActionRegistryEntity::getProjectCode, projectCode)
                .eq(PlatformPageActionRegistryEntity::getPageKey, pageKey)
                .eq(PlatformPageActionRegistryEntity::getActionKey, actionKey)
                .last("LIMIT 1"));
        if (row == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new PageActionCatalogEntry(
                row.getProjectCode(),
                row.getPageKey(),
                row.getActionKey(),
                row.getStatus()
        ));
    }

    public record PageActionCatalogEntry(
            String projectCode,
            String pageKey,
            String actionKey,
            String status
    ) {
    }
}
