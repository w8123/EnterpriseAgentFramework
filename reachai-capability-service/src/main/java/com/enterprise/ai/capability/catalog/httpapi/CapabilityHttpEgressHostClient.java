package com.enterprise.ai.capability.catalog.httpapi;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/** The intranet host allowlist platform administrators keep in 「接口鉴权 · 内网地址白名单」 (owned by Runtime). */
@FeignClient(name = "reachai-runtime-http-egress-hosts", url = "${services.runtime-service.url:http://localhost:18604}")
public interface CapabilityHttpEgressHostClient {

    @GetMapping("/internal/runtime/http-egress-hosts")
    List<String> allowedHosts();
}
