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
     * Disabled by default because most production deployments put /api/embed/**
     * behind a gateway that owns CORS headers.
     */
    private boolean enabled = false;
    private List<String> allowedOrigins = new ArrayList<>();
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("*"));
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "OPTIONS"));
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
    private List<String> exposedHeaders = new ArrayList<>();
    private Boolean allowCredentials = true;
    private Long maxAgeSeconds = 1800L;
}
