package com.enterprise.ai.control.context;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryOutboxMapperContractTest {

    @Test
    void statusAggregationContainsNoIdentityEventOrPayloadColumns() throws Exception {
        Method method = ContextMemoryOutboxMapper.class.getMethod(
                "selectPersonalMemoryStatus", LocalDateTime.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("AS backlog_count"));
        assertTrue(sql.contains("AS dead_count"));
        assertTrue(sql.contains("AS oldest_unpublished_seconds"));
        assertTrue(sql.contains("aggregate_type = 'PERSONAL_MEMORY'"));
        assertTrue(sql.contains("status IN ('PENDING', 'PUBLISHING', 'DEAD')"));
        assertFalse(sql.contains("payload_json"));
        assertFalse(sql.contains("aggregate_id"));
        assertFalse(sql.contains("event_id"));
    }

    @Test
    void erasureAtomicallyScrubsPayloadAndSupersedesEveryRetryableState() throws Exception {
        Method method = ContextMemoryOutboxMapper.class.getMethod(
                "scrubAndSupersedePersonalMemory", String.class, String.class, LocalDateTime.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("payload_json = #{payloadJson}"));
        assertTrue(sql.contains("status IN ('PENDING', 'PUBLISHING', 'DEAD') THEN 'SUPERSEDED'"));
        assertTrue(sql.contains("aggregate_type = 'PERSONAL_MEMORY'"));
        assertTrue(sql.contains("aggregate_id = #{aggregateId}"));
        assertTrue(sql.contains("locked_at = NULL"));
        assertTrue(sql.contains("last_error = NULL"));
        assertFalse(sql.contains("tenant_id"));
        assertFalse(sql.contains("runtime_user"));
        assertFalse(sql.contains("content_sha"));
    }

    @Test
    void crossDomainDeliveryProofIsCorrelationScopedAndPayloadFree() throws Exception {
        Method method = ContextMemoryOutboxMapper.class.getMethod(
                "selectErasureDeliveryStatus", String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        assertTrue(sql.contains("event_type = 'PERSONAL_MEMORY_DELETE'"));
        assertTrue(sql.contains("correlation_id = #{correlationId}"));
        assertTrue(sql.contains("AS pending_count"));
        assertTrue(sql.contains("AS dead_count"));
        assertTrue(sql.contains("MAX(id) AS max_source_version"));
        assertFalse(sql.contains("payload_json"));
        assertFalse(sql.contains("runtime_user"));
        assertFalse(sql.contains("tenant_id"));
    }
}
