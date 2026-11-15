package com.enterprise.ai.external;

/**
 * One passage returned by an external knowledge base. {@code score} is 0..1; providers without a similarity
 * score (ima) rank by position.
 */
public record ExternalKnowledgeHit(String id, String documentId, String documentName, String content, float score) { }
