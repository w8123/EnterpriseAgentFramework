package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiSignatureHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reachai/registry")
public class ReachCapabilitySyncEndpoint {

    private final ReachAiRegistryClient registryClient;
    private final ReachAiRegistryRequestVerifier requestVerifier;

    public ReachCapabilitySyncEndpoint(ReachAiRegistryClient registryClient,
                                       ReachAiRegistryRequestVerifier requestVerifier) {
        this.registryClient = registryClient;
        this.requestVerifier = requestVerifier;
    }

    @PostMapping("/capabilities/sync")
    public ReachAiRegistryClient.ManualCapabilitySyncResponse sync(
            @RequestHeader(value = ReachAiSignatureHeaders.HEADER_APP_KEY, required = false) String appKey,
            @RequestHeader(value = ReachAiSignatureHeaders.HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(value = ReachAiSignatureHeaders.HEADER_NONCE, required = false) String nonce,
            @RequestHeader(value = ReachAiSignatureHeaders.HEADER_SIGNATURE, required = false) String signature) {
        requestVerifier.verify(appKey, timestamp, nonce, signature);
        return registryClient.scanAndSyncCapabilitiesFromPlatformCallback();
    }
}
