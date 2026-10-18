package com.enterprise.ai.service.impl;

import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;

/** 为门面回归组装真实查询与编辑组件，复用原测试的 Mapper 和事务。 */
final class KnowledgeManagementTestServices {
    private KnowledgeManagementTestServices() { }

    static KnowledgeServiceImpl create(FileInfoRepository files, ChunkRepository chunks, KnowledgeOperationsQuery operations, KnowledgeIndexWriteService indexWrites,
            KnowledgeBaseLookup lookup, KnowledgeRetrievalEngine retrieval, KnowledgeFileDeletionService deletion,
            KnowledgeBaseLifecycleService bases) {
        return new KnowledgeServiceImpl(operations, indexWrites, lookup, retrieval, deletion, bases,
                new KnowledgeContentQuery(files, chunks, lookup), new KnowledgeChunkEditingService(chunks));
    }
}
