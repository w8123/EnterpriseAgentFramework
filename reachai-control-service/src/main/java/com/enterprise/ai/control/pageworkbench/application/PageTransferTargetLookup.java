package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageEntity;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** A page locked by its catalog owner for a same-principal transfer transaction. */
@Service
@RequiredArgsConstructor
public class PageTransferTargetLookup {
    private final ProjectPageMapper pages;

    public Optional<Target> forUpdate(String projectCode, String pageKey) {
        var page = pages.selectOne(Wrappers.<ProjectPageEntity>lambdaQuery()
                .eq(ProjectPageEntity::getProjectCode, projectCode)
                .eq(ProjectPageEntity::getPageKey, pageKey)
                .last("LIMIT 1 FOR UPDATE"));
        return page == null ? Optional.empty() : Optional.of(new Target(page.getProjectCode(), page.getPageKey(),
                page.getLifecycleStatus(), page.getRoutePattern()));
    }

    public record Target(String projectCode, String pageKey, String lifecycleStatus, String routePattern) { }
}
