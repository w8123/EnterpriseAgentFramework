package com.enterprise.ai.control.platform;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformEmbedCorsWebConfigTest {

    @Test
    void leavesEmbedCorsUnregisteredByDefault() throws Exception {
        PlatformEmbedCorsProperties properties = new PlatformEmbedCorsProperties();
        CorsRegistry registry = new CorsRegistry();

        new PlatformEmbedCorsWebConfig(properties).addCorsMappings(registry);

        assertTrue(corsConfigurations(registry).isEmpty());
    }

    @Test
    void registersEmbedCorsOnlyWhenEnabled() throws Exception {
        PlatformEmbedCorsProperties properties = new PlatformEmbedCorsProperties();
        properties.setEnabled(true);
        properties.setAllowedOriginPatterns(List.of("https://gateway.example.com"));
        properties.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        CorsRegistry registry = new CorsRegistry();

        new PlatformEmbedCorsWebConfig(properties).addCorsMappings(registry);

        Map<String, CorsConfiguration> mappings = corsConfigurations(registry);
        assertTrue(mappings.containsKey("/api/embed/**"));
        CorsConfiguration configuration = mappings.get("/api/embed/**");
        assertEquals(List.of("https://gateway.example.com"), configuration.getAllowedOriginPatterns());
        assertEquals(List.of("GET", "POST", "OPTIONS"), configuration.getAllowedMethods());
        assertFalse(Boolean.FALSE.equals(configuration.getAllowCredentials()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CorsConfiguration> corsConfigurations(CorsRegistry registry) throws Exception {
        Method method = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
        method.setAccessible(true);
        return (Map<String, CorsConfiguration>) method.invoke(registry);
    }
}
