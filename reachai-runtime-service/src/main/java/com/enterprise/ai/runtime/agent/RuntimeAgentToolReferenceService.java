package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.workflow.RuntimeAgentEntryEntity;
import com.enterprise.ai.runtime.workflow.RuntimeAgentEntryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeAgentToolReferenceService {

    private final RuntimeAgentEntryMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<AgentToolReference> listAgentToolReferences() {
        return mapper.selectList(Wrappers.<RuntimeAgentEntryEntity>lambdaQuery()).stream()
                .map(entity -> {
                    Map<String, Object> config = parseConfig(entity.getEntryConfigJson());
                    return new AgentToolReference(
                            entity.getId(),
                            entity.getName(),
                            parseStringList(config.get("tools")),
                            parseStringList(config.get("skills")));
                })
                .toList();
    }

    private Map<String, Object> parseConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<String> parseStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    out.add(String.valueOf(item).trim());
                }
            }
            return List.copyOf(out);
        }
        if (raw instanceof String text && StringUtils.hasText(text)) {
            try {
                List<String> parsed = objectMapper.readValue(text, new TypeReference<List<String>>() {});
                return parsed == null ? List.of() : parsed.stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .toList();
            } catch (Exception ex) {
                return List.of();
            }
        }
        return List.of();
    }

    public record AgentToolReference(String agentId, String agentName, List<String> tools, List<String> skills) {
    }
}
