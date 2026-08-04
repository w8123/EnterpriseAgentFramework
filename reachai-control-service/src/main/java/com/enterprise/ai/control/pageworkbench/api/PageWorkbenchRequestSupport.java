package com.enterprise.ai.control.pageworkbench.api;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

final class PageWorkbenchRequestSupport {

    private PageWorkbenchRequestSupport() {
    }

    static String publicBaseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .build()
                .toUriString();
    }
}
