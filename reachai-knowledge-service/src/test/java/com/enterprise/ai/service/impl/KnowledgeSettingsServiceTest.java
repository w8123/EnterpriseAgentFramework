package com.enterprise.ai.service.impl;

import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSettingsServiceTest {
    private KnowledgeQueryTestDatabase db;
    private KnowledgeSettingsService settings;

    @BeforeEach void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base"), KnowledgeBaseRepository.class);
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,workspace_id,project_code,scope,embedding_model_instance_id,llm_model_instance_id,rerank_model_instance_id,access_mode,chunk_size,chunk_overlap,split_type,search_mode,top_k,rerank_enabled,status) VALUES (7,'Original','kb','physical-a','team','orders','PROJECT','embed','llm','rerank','FILE_GRANTS',800,100,'FIXED','hybrid',5,1,1)");
        var target = new KnowledgeSettingsService(db.mapper(KnowledgeBaseRepository.class), new ObjectMapper());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()), new AnnotationTransactionAttributeSource()));
        settings = (KnowledgeSettingsService) proxy.getProxy();
    }
    @AfterEach void close() { if (db != null) db.close(); }

    @Test void atomicPatchPreservesUnrelatedIdentityAndReturnsAChangedRevision() {
        var before = settings.context("kb");
        var changes = Map.<String, Object>of("name", "共同设置", "chunkSize", 1200, "topK", 9);
        var preview = settings.preview("kb", revision(before), changes);
        assertEquals("Original", db.jdbc().queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
        var saved = settings.save("kb", revision(before), changes);
        assertEquals(preview.get("after"), saved.get("before"));
        assertNotEquals(before.get("revision"), saved.get("revision"));
        assertEquals("physical-a", db.jdbc().queryForObject("SELECT vector_collection_name FROM knowledge_base WHERE id=7", String.class));
        assertEquals("orders", db.jdbc().queryForObject("SELECT project_code FROM knowledge_base WHERE id=7", String.class));
        assertEquals("共同设置", db.jdbc().queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
        assertEquals(1200, db.jdbc().queryForObject("SELECT chunk_size FROM knowledge_base WHERE id=7", Integer.class));
        assertFalse(new ObjectMapper().valueToTree(saved).toString().contains("physical-a"));
    }

    @Test void invalidPatchNeverPartiallySavesAndIdentityFieldsAreNotWritable() {
        var revision = revision(settings.context("kb"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> settings.save("kb", revision, Map.of("name", "Partial", "chunkSize", 10))).getStatusCode().value());
        for (String field : List.of("scope", "projectCode", "workspaceId", "id", "code", "dimension", "status", "vectorCollectionName")) {
            assertThrows(ResponseStatusException.class, () -> settings.save("kb", revision, Map.of(field, "forged")));
        }
        assertEquals("Original", db.jdbc().queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
    }

    @Test void nullableDescriptionAndRerankModelCanBeClearedInTheSameCommand() {
        var changes = new LinkedHashMap<String, Object>();
        changes.put("description", null); changes.put("rerankModelInstanceId", null); changes.put("rerankEnabled", false);
        settings.save("kb", revision(settings.context("kb")), changes);
        assertNull(db.jdbc().queryForObject("SELECT rerank_model_instance_id FROM knowledge_base WHERE id=7", String.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT rerank_enabled FROM knowledge_base WHERE id=7", Integer.class));
    }

    @Test void concurrentEditsAndScopeMovesInvalidateOldRevisionsEvenWithinTheSameTimestamp() {
        var revision = revision(settings.context("kb"));
        db.jdbc().update("UPDATE knowledge_base SET description='Someone else changed this' WHERE id=7");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> settings.save("kb", revision, Map.of("name", "Lost update"))).getStatusCode().value());
        var refreshed = revision(settings.context("kb"));
        db.jdbc().update("UPDATE knowledge_base SET project_code='finance' WHERE id=7");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> settings.save("kb", refreshed, Map.of("name", "Wrong scope"))).getStatusCode().value());
        assertEquals("Original", db.jdbc().queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
    }

    @Test void reusedBusinessCodeAndRowIdCannotReuseTheOldPhysicalRevision() {
        String revision = revision(settings.context("kb"));
        db.jdbc().update("UPDATE knowledge_base SET vector_collection_name='replacement-physical' WHERE id=7");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> settings.save("kb", revision, Map.of("name", "Stale replacement"))).getStatusCode().value());
    }

    @Test void twoWritersWithTheSameRevisionCannotOverwriteEachOther() throws Exception {
        String revision = revision(settings.context("kb"));
        var ready = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var outcomes = List.of("Human", "AI").stream().map(name -> executor.submit(() -> {
                ready.await();
                try { settings.save("kb", revision, Map.of("name", name)); return 200; }
                catch (ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
            })).toList();
            ready.countDown();
            assertEquals(List.of(200, 409), outcomes.stream().map(result -> {
                try { return result.get(5, TimeUnit.SECONDS); } catch (Exception failure) { throw new RuntimeException(failure); }
            }).sorted().toList());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private String revision(Map<String, Object> context) { return (String) context.get("revision"); }
}
