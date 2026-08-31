package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.enterprise.ai.control.a2a.application.port.A2aRateLimitRepository;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class MybatisA2aRateLimitRepository implements A2aRateLimitRepository {

    private final A2aRateLimitMapper mapper;

    @Override
    public int incrementAndGet(long principalId, long publicationId, LocalDateTime windowStart) {
        if (mapper.increment(principalId, publicationId, windowStart) <= 0) {
            throw new A2aDomainException("A2A_RATE_LIMIT_UNAVAILABLE",
                    "rate-limit state could not be updated");
        }
        Integer current = mapper.current(principalId, publicationId, windowStart);
        if (current == null || current <= 0) {
            throw new A2aDomainException("A2A_RATE_LIMIT_UNAVAILABLE",
                    "rate-limit state could not be read");
        }
        return current;
    }

    @Override
    public int deleteBefore(LocalDateTime cutoff) {
        return mapper.deleteBefore(cutoff);
    }
}
