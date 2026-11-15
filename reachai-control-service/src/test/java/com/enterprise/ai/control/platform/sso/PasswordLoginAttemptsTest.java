package com.enterprise.ai.control.platform.sso;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordLoginAttemptsTest {
    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Test
    void unfinishedAttemptsConsumeBothAccountAndAddressAllowances() {
        var attempts = new PasswordLoginAttempts(100, 5, WINDOW);
        List<PasswordLoginAttempts.Permit> pending = new ArrayList<>();
        for (int i = 0; i < 5; i++) pending.add(attempts.acquire("account", "ip", NOW));
        assertThatThrownBy(() -> attempts.acquire("account", "other-ip", NOW))
                .isInstanceOf(BusinessSsoException.class).hasMessageContaining("试错次数太多");
        assertThatThrownBy(() -> attempts.acquire("other-account", "ip", NOW))
                .isInstanceOf(BusinessSsoException.class).hasMessageContaining("试错次数太多");
        pending.forEach(permit -> attempts.finish(permit, true, false, NOW));
        assertThatThrownBy(() -> attempts.acquire("account", "ip", NOW.plusSeconds(599)))
                .isInstanceOf(BusinessSsoException.class);
        assertThat(attempts.acquire("account", "ip", NOW.plus(WINDOW))).isNotNull();
    }

    @Test
    void capacityCannotBeExceededByConcurrentRequestsWithNewKeys() throws Exception {
        var attempts = new PasswordLoginAttempts(8, 5, WINDOW);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 32)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> {
                        try {
                            attempts.acquire("account-" + i, "ip-" + i, NOW);
                            return true;
                        } catch (BusinessSsoException full) {
                            return false;
                        }
                    }).toList();
            int admitted = 0;
            for (var result : pool.invokeAll(tasks, 5, TimeUnit.SECONDS)) if (result.get()) admitted++;
            assertThat(admitted).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredFailuresFreeCapacityButOutstandingRequestsKeepTheirReservations() {
        var attempts = new PasswordLoginAttempts(2, 5, WINDOW);
        var pending = attempts.acquire("old-account", "old-ip", NOW);
        assertThatThrownBy(() -> attempts.acquire("new-account", "new-ip", NOW.plus(WINDOW)))
                .isInstanceOf(BusinessSsoException.class).hasMessageContaining("登录的人太多");
        attempts.finish(pending, true, false, NOW.plus(WINDOW));
        assertThatThrownBy(() -> attempts.acquire("new-account", "new-ip", NOW.plus(WINDOW)))
                .isInstanceOf(BusinessSsoException.class);
        assertThat(attempts.acquire("new-account", "new-ip", NOW.plus(WINDOW.multipliedBy(2)))).isNotNull();
    }

    @Test
    void successClearsTheAccountFailuresButKeepsEarlierAddressFailures() {
        var attempts = new PasswordLoginAttempts(100, 5, WINDOW);
        for (int i = 0; i < 4; i++) {
            attempts.finish(attempts.acquire("account", "ip", NOW), true, false, NOW);
        }
        attempts.finish(attempts.acquire("account", "ip", NOW), false, true, NOW);
        attempts.finish(attempts.acquire("another-account", "ip", NOW), true, false, NOW);
        assertThatThrownBy(() -> attempts.acquire("account", "ip", NOW))
                .isInstanceOf(BusinessSsoException.class).hasMessageContaining("试错次数太多");
        assertThat(attempts.acquire("account", "other-ip", NOW)).isNotNull();
    }

    @Test
    void unexpectedFailuresReleaseReservationsWithoutLockingOutTheEmployee() {
        var attempts = new PasswordLoginAttempts(2, 5, WINDOW);
        for (int i = 0; i < 10; i++) {
            attempts.finish(attempts.acquire("account-" + i, "ip", NOW), false, false, NOW);
        }
        assertThat(attempts.acquire("new-account", "new-ip", NOW)).isNotNull();
    }
}
