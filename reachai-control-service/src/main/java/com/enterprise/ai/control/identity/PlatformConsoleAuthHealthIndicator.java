package com.enterprise.ai.control.identity;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Non-sensitive readiness for the optional platform-console login surface. */
@Component("platformConsoleAuth")
public class PlatformConsoleAuthHealthIndicator implements HealthIndicator {

    private final PlatformConsoleAuthAvailability availability;

    public PlatformConsoleAuthHealthIndicator(PlatformConsoleAuthAvailability availability) {
        this.availability = availability;
    }

    @Override
    public Health health() {
        PlatformConsoleAuthAvailability.Availability state = availability.current();
        if (!state.available()) {
            return Health.down()
                    .withDetail("code", state.code())
                    .withDetail("loginAvailable", false)
                    .build();
        }
        return Health.up()
                .withDetail("code", state.code())
                .withDetail("loginAvailable", true)
                .build();
    }
}
