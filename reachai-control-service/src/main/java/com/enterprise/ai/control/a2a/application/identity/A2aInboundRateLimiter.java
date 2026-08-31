package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.application.port.A2aRateLimitRepository;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Database-backed fixed-window limiter shared by every Control-service replica. */
@Service
@RequiredArgsConstructor
public class A2aInboundRateLimiter {

    private final A2aRateLimitRepository repository;
    private final Clock clock;

    @Transactional
    public void requirePermit(A2aInboundCallContext call) {
        LocalDateTime window = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        int count = repository.incrementAndGet(
                call.principal().principalId(), call.publication().publicationId(), window);
        if (count > call.trustProfile().rateLimitPerMinute()) {
            throw new A2aDomainException("A2A_RATE_LIMIT_EXCEEDED",
                    "the Trust Profile request rate limit has been reached");
        }
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    @Transactional
    public void cleanupExpiredWindows() {
        repository.deleteBefore(LocalDateTime.now(clock).minusDays(2));
    }
}
