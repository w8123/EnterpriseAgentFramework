package com.enterprise.ai.control.client.health;

import feign.Request;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Dedicated short timeouts for internal health Feign clients.
 * Must not inherit the default 135s business readTimeout.
 * Not annotated with {@code @Configuration} so Spring Cloud OpenFeign
 * does not pull these beans into the parent application context.
 */
public class InternalHealthFeignConfig {

    public static final int CONNECT_TIMEOUT_MS = 1_500;
    public static final int READ_TIMEOUT_MS = 2_000;

    @Bean
    public Request.Options healthRequestOptions() {
        return new Request.Options(
                Duration.ofMillis(CONNECT_TIMEOUT_MS),
                Duration.ofMillis(READ_TIMEOUT_MS),
                true
        );
    }
}
