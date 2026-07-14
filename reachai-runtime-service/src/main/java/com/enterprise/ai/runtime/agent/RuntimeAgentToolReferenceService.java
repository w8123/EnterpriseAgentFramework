package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RuntimeAgentToolReferenceService {

    private final RuntimeAgentMapper mapper;
    private final RuntimeAgentConfigService configService;

    public List<AgentToolReference> listAgentToolReferences() {
        return mapper.selectList(Wrappers.<RuntimeAgentEntity>lambdaQuery()).stream()
                .map(entity -> {
                    List<String> workflowTools = configService.resolveActive(entity.getId())
                            .map(config -> configService.resolveActiveTools(entity.getId(), config).stream()
                                    .map(RuntimeAgentWorkflowToolEntity::getToolName)
                                    .toList())
                            .orElse(List.of());
                    return new AgentToolReference(
                            entity.getId(),
                            entity.getName(),
                            workflowTools,
                            List.of());
                })
                .toList();
    }

    public record AgentToolReference(String agentId, String agentName, List<String> tools, List<String> skills) {
    }
}
