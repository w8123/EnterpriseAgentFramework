package com.enterprise.ai.rag.impl;

import com.enterprise.ai.domain.dto.RagRequest;
import com.enterprise.ai.domain.dto.RagResponse;
import com.enterprise.ai.rag.LlmService;
import com.enterprise.ai.rag.PromptBuilder;
import com.enterprise.ai.rag.RagService;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCore;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreRequest;
import com.enterprise.ai.retrieval.KnowledgeRetrievalCoreResponse;
import com.enterprise.ai.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {
    private final KnowledgeRetrievalCore retrieval;
    private final KnowledgeService knowledgeService;
    private final LlmService llmService;
    private final PromptBuilder promptBuilder;

    @Override
    public RagResponse query(RagRequest request) {
        var evidence = retrieveEvidence(request);
        if (evidence.getItems().isEmpty()) return empty("未找到与您问题相关的知识库内容。");
        var references = evidence.toSimilarItems();
        String code = references.get(0).getKnowledgeBaseCode();
        var kb = knowledgeService.resolveKnowledgeBases(List.of(code)).stream()
                .filter(candidate -> code.equals(candidate.getCode())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("回答知识库已失效"));
        if (kb.getLlmModelInstanceId() == null || kb.getLlmModelInstanceId().isBlank()) {
            throw new IllegalArgumentException("llmModelInstanceId is required for knowledge base: " + code);
        }
        String prompt = promptBuilder.build(request.getQuestion(), references);
        if (!retrieval.isCurrent(evidence)) return changed();
        String answer = llmService.chat(prompt, kb.getLlmModelInstanceId().trim());
        if (!retrieval.isCurrent(evidence)) return changed();
        var response = new RagResponse(); response.setReferences(references); response.setAnswer(answer); return response;
    }

    @Override
    public RagResponse retrieve(RagRequest request) {
        var evidence = retrieveEvidence(request);
        var response = new RagResponse(); response.setReferences(evidence.toSimilarItems()); response.setAnswer(null); return response;
    }

    private KnowledgeRetrievalCoreResponse retrieveEvidence(RagRequest request) {
        return retrieval.retrieve(KnowledgeRetrievalCoreRequest.builder()
                .query(request.getQuestion()).knowledgeBaseCodes(request.getKnowledgeBaseCodes()).userId(request.getUserId())
                .topK(request.getTopK()).scoreThreshold(request.getScoreThreshold()).searchMode("vector")
                .rerankEnabled(false).directReturnEnabled(false).recordHit(false).build());
    }

    private RagResponse changed() { return empty("检索内容的授权或版本已变化，请重新检索。"); }
    private RagResponse empty(String reason) {
        var response = new RagResponse(); response.setAnswer(reason); response.setReferences(List.of()); return response;
    }
}
