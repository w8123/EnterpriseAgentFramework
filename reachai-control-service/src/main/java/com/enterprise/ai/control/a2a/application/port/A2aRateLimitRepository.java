package com.enterprise.ai.control.a2a.application.port;

import java.time.LocalDateTime;

public interface A2aRateLimitRepository {

    int incrementAndGet(long principalId, long publicationId, LocalDateTime windowStart);

    int deleteBefore(LocalDateTime cutoff);
}
