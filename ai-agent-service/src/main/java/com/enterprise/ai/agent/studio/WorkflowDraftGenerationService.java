package com.enterprise.ai.agent.studio;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowDraftGenerationService {

    private final List<WorkflowDraftGenerator> generators;

    public WorkflowDraftGenerationResult generate(WorkflowDraftGenerationRequest request) {
        return generators.stream()
                .filter(generator -> generator.supports(request))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有可用的流程草稿生成器，请检查模型实例和生成器配置"))
                .generate(request);
    }
}
