package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository.PublishedCard;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalContext;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;

public record A2aInboundCallContext(
        PublishedCard publication,
        A2aPrincipalContext principal,
        A2aTrustProfile trustProfile) {
}
