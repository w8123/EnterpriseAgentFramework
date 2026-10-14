package com.enterprise.ai.control.a2a.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class A2aPublishedReferenceReaderTest {
    final A2aPublicationMapper publications = mock(A2aPublicationMapper.class);
    final A2aPublicationRevisionMapper revisions = mock(A2aPublicationRevisionMapper.class);
    final A2aPublishedReferenceReader reader = new A2aPublishedReferenceReader(publications, revisions);

    @org.junit.jupiter.api.BeforeEach void entityMetadata() {
        for (Class<?> type : List.of(A2aPublicationEntity.class, A2aPublicationRevisionEntity.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                    new com.baomidou.mybatisplus.core.MybatisConfiguration(), "a2a-reference"), type);
    }

    @Test void batchesOnlyCurrentRevisionsAndDetectsWrongOwner() {
        var first = publication(1L, 11L); var second = publication(2L, 21L);
        var one = revision(11L, 1L); var two = revision(21L, 2L);
        when(publications.selectList(any())).thenReturn(List.of(first, second));
        when(revisions.selectList(any())).thenReturn(List.of(one, two));
        var evidence = reader.inspect();
        assertTrue(evidence.complete()); assertEquals(2, evidence.bindings().size());
        verify(revisions).selectList(any()); verify(revisions, never()).selectById(any());
        verify(revisions, never()).selectBatchIds(any());
        two.setPublicationId(99L);
        assertFalse(reader.inspect().complete());
        when(revisions.selectList(any())).thenReturn(List.of(one));
        assertFalse(reader.inspect().complete());
    }

    private A2aPublicationEntity publication(Long id, Long current) {
        var value = new A2aPublicationEntity(); value.setId(id); value.setCurrentRevisionId(current); value.setAgentId("a1"); return value;
    }
    private A2aPublicationRevisionEntity revision(Long id, Long publication) {
        var value = new A2aPublicationRevisionEntity(); value.setId(id); value.setPublicationId(publication); value.setAgentConfigVersionId(id); return value;
    }
}
