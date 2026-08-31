package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository.PublishedCard;
import com.enterprise.ai.control.a2a.application.port.A2aRateLimitRepository;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalContext;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aInboundRateLimiterTest {

    @Test
    void usesSharedUtcMinuteWindowAndRejectsOverflow() {
        A2aRateLimitRepository repository = mock(A2aRateLimitRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T00:00:31Z"), ZoneOffset.UTC);
        A2aInboundRateLimiter limiter = new A2aInboundRateLimiter(repository, clock);
        PublishedCard card = mock(PublishedCard.class);
        when(card.publicationId()).thenReturn(9L);
        A2aTrustProfile trust = mock(A2aTrustProfile.class);
        when(trust.rateLimitPerMinute()).thenReturn(10);
        A2aPrincipalContext principal = new A2aPrincipalContext(
                7L, "principal-a", A2aPrincipalType.REMOTE_AGENT,
                "tenant-a", A2aAuthenticationMethod.API_KEY,
                A2aTrustLevel.TRUSTED, java.util.Set.of(), null);
        A2aInboundCallContext call = new A2aInboundCallContext(card, principal, trust);
        LocalDateTime window = LocalDateTime.of(2026, 8, 24, 0, 0);
        when(repository.incrementAndGet(7L, 9L, window)).thenReturn(11);

        assertThatThrownBy(() -> limiter.requirePermit(call))
                .hasMessageContaining("rate limit");
        verify(repository).incrementAndGet(eq(7L), eq(9L), eq(window));
    }
}
