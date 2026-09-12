package com.enterprise.ai.security;

import java.util.Objects;

/** Current published metadata and grant, resolved together by the owning Knowledge database. */
public record AuthorizedKnowledgeChunk(Long chunkId, Long grantId, String grantGeneration, Long fileRecordId,
                                       String fileGeneration, Long knowledgeBaseId,
                                       String fileId, String collectionName, String knowledgeBaseCode,
                                       String vectorId, String content, String fileName) {
    public boolean matches(Long candidateId, String candidateVector, String candidateFile,
                           String candidateKnowledgeBase, String candidateContent) {
        return Objects.equals(chunkId, candidateId) && Objects.equals(vectorId, candidateVector)
                && Objects.equals(fileId, candidateFile) && Objects.equals(knowledgeBaseCode, candidateKnowledgeBase)
                && Objects.equals(content, candidateContent);
    }
}
