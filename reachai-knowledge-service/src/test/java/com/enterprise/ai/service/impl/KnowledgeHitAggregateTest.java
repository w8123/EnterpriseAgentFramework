package com.enterprise.ai.service.impl;

import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeHitAggregateTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void statisticsAndCatalogSumOnlyTheirKnowledgeBaseWithoutLoadingChunkBodies(boolean catalog) throws Exception {
        try (var db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk"),
                KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class)) {
            var bases = db.mapper(KnowledgeBaseRepository.class);
            var chunks = spy(db.mapper(ChunkRepository.class));
            db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'target','target','target'),(8,'other','other','other'),(9,'empty','empty','empty')");
            String body = "长文本".repeat(1000);
            db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,hit_count) VALUES ('a',7,?,7),('b',7,?,0),('c',7,?,11),('d',8,?,999)", body, body, body, body);
            var query = new KnowledgeOperationsQuery(bases, db.mapper(FileInfoRepository.class), chunks,
                    mock(KnowledgeTagRepository.class), mock(KnowledgeQuestionRepository.class), mock(KnowledgeHitLogRepository.class), new KnowledgeBaseLookup(bases));
            if (catalog) {
                var all = query.listAll();
                assertEquals(18, all.stream().filter(kb -> kb.getCode().equals("target")).findFirst().orElseThrow().getHitCount());
                assertEquals(0, all.stream().filter(kb -> kb.getCode().equals("empty")).findFirst().orElseThrow().getHitCount());
                assertEquals(999, all.stream().filter(kb -> kb.getCode().equals("other")).findFirst().orElseThrow().getHitCount());
            } else {
                assertEquals(18, query.getStats("target").getHitCount());
                assertEquals(0, query.getStats("empty").getHitCount());
                assertEquals(999, query.getStats("other").getHitCount());
            }
            verify(chunks, never()).selectList(any());
        }
    }
}
