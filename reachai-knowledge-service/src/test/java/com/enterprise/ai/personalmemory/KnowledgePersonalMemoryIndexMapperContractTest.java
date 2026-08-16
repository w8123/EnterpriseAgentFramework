package com.enterprise.ai.personalmemory;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgePersonalMemoryIndexMapperContractTest {

    @Test
    void newerProjectionGuardAndDeleteScrubAreAtomic() throws Exception {
        Method method = KnowledgePersonalMemoryIndexMapper.class.getMethod(
                "updateIfNewer", KnowledgePersonalMemoryIndexEntity.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Update.class).value()));

        assertTrue(sql.contains("source_version < #{item.sourceVersion}"));
        assertTrue(sql.contains("WHEN #{item.status} = 'DELETED' THEN NULL"));
        assertTrue(sql.contains("embedding_vector = #{item.embeddingVector}"));
    }

    @Test
    void workerClaimSupportsLeaseRecoveryAndModelRebuild() throws Exception {
        Method method = KnowledgePersonalMemoryIndexMapper.class.getMethod(
                "claimEmbedding", Long.class, Long.class, String.class,
                int.class, LocalDateTime.class, LocalDateTime.class, String.class, String.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Update.class).value()));

        assertTrue(sql.contains("embedding_claim_until IS NULL OR embedding_claim_until < #{now}"));
        assertTrue(sql.contains("embedding_model_instance_id <> #{modelInstanceId}"));
        assertTrue(sql.contains("source_version = #{sourceVersion}"));
        assertTrue(sql.contains("embedding_attempts = #{attempt}"));
    }

    @Test
    void completionRequiresTheExactSourceAndClaimToken() throws Exception {
        Method method = KnowledgePersonalMemoryIndexMapper.class.getMethod(
                "completeEmbedding", Long.class, Long.class, String.class, byte[].class,
                String.class, Integer.class, String.class, String.class, LocalDateTime.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Update.class).value()));

        assertTrue(sql.contains("source_version = #{sourceVersion}"));
        assertTrue(sql.contains("embedding_claim_token = #{claimToken}"));
        assertTrue(sql.contains("status = 'ACTIVE'"));
    }

    @Test
    void candidateSelectionExcludesExpiredAndCurrentReadyRows() throws Exception {
        Method method = KnowledgePersonalMemoryIndexMapper.class.getMethod(
                "selectEmbeddingCandidates", LocalDateTime.class, String.class, String.class, int.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Select.class).value()));

        assertTrue(sql.contains("status = 'ACTIVE'"));
        assertTrue(sql.contains("expires_at IS NULL OR expires_at > #{now}"));
        assertTrue(sql.contains("embedding_status = 'READY'"));
        assertTrue(sql.contains("embedding_vector IS NULL"));
        assertTrue(sql.contains("embedding_format <> #{format}"));
    }

    @Test
    void statusAggregationContainsNoIdentityColumnsAndChecksDeletedVectorScrub() throws Exception {
        Method method = KnowledgePersonalMemoryIndexMapper.class.getMethod(
                "selectEmbeddingStatus", LocalDateTime.class, String.class, String.class);
        String sql = normalize(String.join(" ", method.getAnnotation(Select.class).value()));

        assertTrue(sql.contains("AS ready_count"));
        assertTrue(sql.contains("AS oldest_pending_seconds"));
        assertTrue(sql.contains("AS unsafe_deleted_vector_count"));
        assertTrue(sql.contains("deleted_projection.embedding_vector IS NOT NULL"));
        assertTrue(!sql.contains("runtime_user_hash AS"));
        assertTrue(!sql.contains("tenant_id AS"));
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
