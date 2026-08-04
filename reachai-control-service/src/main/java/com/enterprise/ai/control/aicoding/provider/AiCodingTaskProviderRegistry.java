package com.enterprise.ai.control.aicoding.provider;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiCodingTaskProviderRegistry {

    private final Map<String, AiCodingTaskKindProvider> providers;

    public AiCodingTaskProviderRegistry(List<AiCodingTaskKindProvider> candidates) {
        Map<String, AiCodingTaskKindProvider> registered = new LinkedHashMap<>();
        for (AiCodingTaskKindProvider candidate : candidates) {
            String kind = AiCodingTaskValues.requiredKey(candidate.kind(), "provider.kind");
            AiCodingTaskKindProvider previous = registered.putIfAbsent(kind, candidate);
            if (previous != null) {
                throw new IllegalStateException("duplicate AI Coding task provider: " + kind);
            }
            if (!kind.equals(AiCodingTaskValues.requiredKey(
                    candidate.contract().taskKind(),
                    "provider.contract.taskKind"))) {
                throw new IllegalStateException(
                        "AI Coding provider kind and contract taskKind differ: " + kind);
            }
        }
        this.providers = Map.copyOf(registered);
    }

    public AiCodingTaskKindProvider require(String kind) {
        String normalized = AiCodingTaskValues.requiredKey(kind, "taskKind");
        AiCodingTaskKindProvider provider = providers.get(normalized);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "unsupported AI Coding taskKind: " + normalized);
        }
        return provider;
    }

    public List<String> kinds() {
        return providers.keySet().stream().sorted().toList();
    }
}
