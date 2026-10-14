package com.enterprise.ai.service.impl;

import com.enterprise.ai.bizindex.domain.dto.BizIndexRequest;
import com.enterprise.ai.bizindex.repository.BusinessIndexAttachmentRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRecordRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRepository;
import com.enterprise.ai.bizindex.service.impl.BizIndexServiceImpl;
import com.enterprise.ai.bizindex.template.TemplateEngine;
import com.enterprise.ai.bizindex.vector.BizVectorService;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BizIndexDimensionTest {
    private KnowledgeQueryTestDatabase db;
    private EmbeddingService embeddings;
    private BizVectorService vectors;
    private BizIndexServiceImpl service;

    @BeforeEach
    void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_business_index"), BusinessIndexRepository.class);
        embeddings = mock(EmbeddingService.class);
        vectors = mock(BizVectorService.class);
        service = new BizIndexServiceImpl(db.mapper(BusinessIndexRepository.class),
                mock(BusinessIndexRecordRepository.class), mock(BusinessIndexAttachmentRepository.class),
                vectors, embeddings, new TemplateEngine());
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void missingDimensionUsesTheSelectedEmbeddingBeforeInsertingAnIndex() {
        when(embeddings.embed(eq("embedding-selected"), anyString())).thenAnswer(call -> {
            assertEquals(0, count());
            verifyNoInteractions(vectors);
            return List.of(0.1f, 0.2f, 0.3f);
        });
        service.create(request());
        var index = db.mapper(BusinessIndexRepository.class).selectOne(null);
        assertEquals(3, index.getDimension());
        assertEquals("embedding-selected", index.getEmbeddingModelInstanceId());
        verify(vectors).ensureCollection("dimension_probe", 3);
    }

    @Test
    void explicitDimensionAvoidsAnExtraProviderCall() {
        var request = request();
        request.setDimension(1024);
        service.create(request);
        assertEquals(1024, db.mapper(BusinessIndexRepository.class).selectOne(null).getDimension());
        verify(vectors).ensureCollection("dimension_probe", 1024);
        verifyNoInteractions(embeddings);
    }

    @Test
    void providerFailureOrInvalidVectorDoesNotInsertOrCreateACollection() {
        when(embeddings.embed(eq("embedding-selected"), anyString())).thenThrow(new IllegalStateException("Unavailable"));
        assertThrows(IllegalStateException.class, () -> service.create(request()));
        assertEquals(0, count());
        reset(embeddings);
        when(embeddings.embed(eq("embedding-selected"), anyString())).thenReturn(List.of(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> service.create(request()));
        assertEquals(0, count());
        verifyNoInteractions(vectors);
    }

    private int count() {
        return db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_business_index", Integer.class);
    }

    private BizIndexRequest request() {
        var request = new BizIndexRequest();
        request.setIndexCode("dimension_probe");
        request.setIndexName("业务索引维度实验");
        request.setSourceSystem("public-test");
        request.setTextTemplate("任务：{title}");
        request.setFieldSchema("[{\"name\":\"title\",\"type\":\"string\"}]");
        request.setEmbeddingModelInstanceId("embedding-selected");
        return request;
    }
}
