package com.enterprise.ai.runtime.client.control;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "reachai-control-service", url = "${services.control-service.url:http://localhost:18603}")
public interface RuntimeControlCatalogClient {

    @GetMapping("/internal/control/page-actions/{projectCode}/{pageKey}/{actionKey}")
    PageActionCatalogEntry getPageAction(@PathVariable("projectCode") String projectCode,
                                         @PathVariable("pageKey") String pageKey,
                                         @PathVariable("actionKey") String actionKey);

    record PageActionCatalogEntry(
            String projectCode,
            String pageKey,
            String actionKey,
            String status
    ) {
    }
}
