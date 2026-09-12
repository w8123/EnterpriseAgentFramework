package com.enterprise.ai.service.impl;

import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

final class KnowledgeIndexTestSupport {
    static KnowledgeBaseLifecycleService baseLifecycle(KnowledgeQueryTestDatabase db,VectorService vectors,
            KnowledgeFileDeletionService deletion,KnowledgeTagRepository tags,KnowledgeQuestionRepository questions) {
        var manager=new DataSourceTransactionManager(db.jdbc().getDataSource());
        var store=collectionStore(db);
        return new KnowledgeBaseLifecycleService(db.mapper(KnowledgeBaseRepository.class),tags, new com.enterprise.ai.service.impl.KnowledgeQuestionService(db.mapper(KnowledgeBaseRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.repository.ChunkRepository.class), questions, manager),deletion,store,vectors,manager);
    }

    static KnowledgeCollectionLifecycleStore collectionStore(KnowledgeQueryTestDatabase db) {
        return new KnowledgeCollectionLifecycleStore(db.mapper(KnowledgeCollectionLifecycleRepository.class),db.mapper(KnowledgeBaseRepository.class),db.mapper(ChunkRepository.class),
                new DataSourceTransactionManager(db.jdbc().getDataSource()));
    }
    static DocumentIndexExecutionStore executions(org.mybatis.spring.SqlSessionTemplate session,
            org.springframework.transaction.PlatformTransactionManager manager) {
        for(var mapper:java.util.List.of(KnowledgeBaseRepository.class,FileInfoRepository.class,ChunkRepository.class)) {
            if(!session.getConfiguration().hasMapper(mapper)) session.getConfiguration().addMapper(mapper);
        }
        return new DocumentIndexExecutionStore(session.getMapper(DocumentImportJobRepository.class),session.getMapper(DocumentIndexExecutionRepository.class),
                session.getMapper(KnowledgeBaseRepository.class),session.getMapper(FileInfoRepository.class),session.getMapper(ChunkRepository.class),manager);
    }

    static DocumentIndexExecutionStore executions(KnowledgeQueryTestDatabase db) {
        return new DocumentIndexExecutionStore(db.mapper(DocumentImportJobRepository.class),db.mapper(DocumentIndexExecutionRepository.class),
                db.mapper(KnowledgeBaseRepository.class),db.mapper(FileInfoRepository.class),db.mapper(ChunkRepository.class),
                new DataSourceTransactionManager(db.jdbc().getDataSource()));
    }

    static MetadataPersistStep metadata(KnowledgeQueryTestDatabase db,DocumentIndexExecutionStore store) {
        return new MetadataPersistStep(db.mapper(KnowledgeBaseRepository.class),db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class),new ObjectMapper(),new DocumentImportPublicationGuard(db.mapper(DocumentImportJobRepository.class),store, deletion(db)), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
    }

    static KnowledgeFileDeletionService deletion(KnowledgeQueryTestDatabase db) {
        return deletion(db,org.mockito.Mockito.mock(com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore.class));
    }

    static KnowledgeFileDeletionService deletion(KnowledgeQueryTestDatabase db,com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore artifacts) {
        return deletion(db,artifacts,org.mockito.Mockito.mock(UserFilePermissionRepository.class),org.mockito.Mockito.mock(KnowledgeQuestionRepository.class));
    }

    static KnowledgeFileDeletionService deletion(KnowledgeQueryTestDatabase db,com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore artifacts,
            UserFilePermissionRepository permissions,KnowledgeQuestionRepository questions) {
        return new KnowledgeFileDeletionService(db.mapper(KnowledgeBaseRepository.class),db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class),db.mapper(DocumentImportJobRepository.class),db.mapper(DocumentIndexExecutionRepository.class),
                executions(db),artifacts,permissions, new com.enterprise.ai.service.impl.KnowledgeQuestionService(db.mapper(KnowledgeBaseRepository.class), db.mapper(ChunkRepository.class), questions, new DataSourceTransactionManager(db.jdbc().getDataSource())),new DataSourceTransactionManager(db.jdbc().getDataSource()), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
    }

    static KnowledgeIndexWriteService writer(KnowledgeQueryTestDatabase db,EmbeddingService embedding,VectorService vectors) {
        var store=executions(db);
        return new KnowledgeIndexWriteService(new KnowledgeBaseLookup(db.mapper(KnowledgeBaseRepository.class)),
                db.mapper(FileInfoRepository.class),db.mapper(ChunkRepository.class),embedding,new VectorStoreStep(vectors,store),
                metadata(db,store),store,new DataSourceTransactionManager(db.jdbc().getDataSource()));
    }
}
