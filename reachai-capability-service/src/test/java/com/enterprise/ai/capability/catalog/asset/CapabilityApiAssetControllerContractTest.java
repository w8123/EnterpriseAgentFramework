package com.enterprise.ai.capability.catalog.asset;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CapabilityApiAssetControllerContractTest {

    @Test
    void ownsApiAssetsRouteLocally() {
        RequestMapping mapping = CapabilityApiAssetController.class.getAnnotation(RequestMapping.class);

        assertArrayEquals(new String[] {"/api/api-assets"}, mapping.value());
    }
}
