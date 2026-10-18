package com.enterprise.ai.control.platform;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "reachai.embed.cors")
public class PlatformEmbedCorsProperties {

    /**
     * Browser origins normally come from the project embed policies. Enable this
     * only to allow extra origins (for example a gateway) on top of them.
     */
    private boolean enabled = false;
    private List<String> allowedOrigins = new ArrayList<>();
    private List<String> allowedOriginPatterns = new ArrayList<>();
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "DELETE", "OPTIONS"));
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
    private List<String> exposedHeaders = new ArrayList<>();
    private Boolean allowCredentials = true;
    private Long maxAgeSeconds = 1800L;
}
