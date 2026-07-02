package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectAgentReferenceReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CapabilityScanProjectAgentReferenceReader implements ScanProjectAgentReferenceReader {

    private final CapabilityRuntimeAgentReferenceClient client;

    @Override
    public List<AgentToolReference> listAgentToolReferences() {
        return client.listAgentToolReferences().stream()
                .map(ref -> new AgentToolReference(
                        ref.agentId(),
                        ref.agentName(),
                        safeList(ref.tools()),
                        safeList(ref.skills())))
                .toList();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
