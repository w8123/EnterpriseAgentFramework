package com.enterprise.ai.bizindex.service;

import com.enterprise.ai.bizindex.domain.dto.BizUpsertRequest;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndex;
import com.enterprise.ai.bizindex.repository.BusinessIndexAttachmentRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRecordRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRepository;
import com.enterprise.ai.bizindex.service.impl.BizIndexDataServiceImpl;
import com.enterprise.ai.bizindex.template.TemplateEngine;
import com.enterprise.ai.bizindex.vector.BizVectorService;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.chunk.ChunkStrategyFactory;
import com.enterprise.ai.pipeline.parser.DocumentParserFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BizIndexProjectScopeTest {

    @Test
    void projectCredentialCannotMutateAnotherProjectsIndex() {
        BusinessIndexRepository indexes = mock(BusinessIndexRepository.class);
        BusinessIndex index = new BusinessIndex();
        index.setIndexCode("orders_idx");
        index.setProjectCode("other-project");
        index.setStatus("ACTIVE");
        when(indexes.selectOne(any())).thenReturn(index);
        BizIndexDataServiceImpl service = new BizIndexDataServiceImpl(
                indexes,
                mock(BusinessIndexRecordRepository.class),
                mock(BusinessIndexAttachmentRepository.class),
                mock(BizVectorService.class),
                mock(EmbeddingService.class),
                mock(TemplateEngine.class),
                mock(DocumentParserFactory.class),
                mock(ChunkStrategyFactory.class),
                new ObjectMapper());

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.upsertForProject("orders", "orders_idx", new BizUpsertRequest()));

        assertEquals(403, failure.getStatusCode().value());
    }
}
