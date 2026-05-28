package com.enterprise.ai.spring.registry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eaf")
public class EafEmbedTokenEndpoint {

    private final EafEmbedTokenService service;

    public EafEmbedTokenEndpoint(EafEmbedTokenService service) {
        this.service = service;
    }

    @GetMapping("/embed-token")
    public EafEmbedTokenResponse embedToken(@RequestParam String agentId,
                                            @RequestParam String pageInstanceId,
                                            @RequestParam(required = false) String route,
                                            @RequestParam String origin) {
        return service.issueToken(new EafEmbedTokenRequest(agentId, pageInstanceId, route, origin));
    }
}
