package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.publication.A2aAgentCardSnapshot;
import com.enterprise.ai.control.a2a.domain.publication.A2aProtocolSkill;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;

import java.util.List;

public interface A2aAgentCardGenerator {

    A2aAgentCardSnapshot generate(Source source, A2aTrustProfile trustProfile);

    record Source(
            String name,
            String description,
            String agentVersion,
            String providerOrganization,
            String providerUrl,
            String documentationUrl,
            String iconUrl,
            String publicOrigin,
            String protocolBasePath,
            boolean streamingSupported,
            boolean pushNotificationsSupported,
            boolean extendedCardSupported,
            List<String> defaultInputModes,
            List<String> defaultOutputModes,
            List<A2aProtocolSkill> protocolSkills) {
    }
}
