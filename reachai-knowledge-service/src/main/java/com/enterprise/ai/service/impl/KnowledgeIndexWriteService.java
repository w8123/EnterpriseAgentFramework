package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexTargetSnapshot;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireEmbeddingModelInstanceId;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireVectorCollectionName;

/** External work has no ambient database transaction; only final visibility is transactional. */
@Slf4j
@Service
public class KnowledgeIndexWriteService {
    private final KnowledgeBaseLookup bases;
    private final FileInfoRepository files;
    private final ChunkRepository chunks;
    private final EmbeddingService embedding;
    private final VectorStoreStep vectors;
    private final MetadataPersistStep metadata;
    private final DocumentIndexExecutionStore executions;
    private final TransactionTemplate suspended;
    private final TransactionTemplate publication;

    public KnowledgeIndexWriteService(KnowledgeBaseLookup bases, FileInfoRepository files, ChunkRepository chunks,
            EmbeddingService embedding, VectorStoreStep vectors, MetadataPersistStep metadata,
            DocumentIndexExecutionStore executions, PlatformTransactionManager transactionManager) {
        this.bases=bases; this.files=files; this.chunks=chunks; this.embedding=embedding;
        this.vectors=vectors; this.metadata=metadata; this.executions=executions;
        suspended=new TransactionTemplate(transactionManager);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        publication=new TransactionTemplate(transactionManager);
        publication.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void importChunks(KnowledgeImportRequest request) {
        suspended.executeWithoutResult(status -> {
            if (request == null) throw new IllegalArgumentException("导入请求不能为空");
            var kb=bases.requireByCode(request.getKnowledgeBaseCode());
            var context=context(kb,request.getFileId(),request.getFileName(),request.getChunks(),"DIRECT");
            context.setVectors(embedding.embedBatch(requireEmbeddingModelInstanceId(kb),context.getChunks()));
            writeAndPublish(context,()->metadata.process(context));
        });
    }

    public void reembedChunk(Long chunkId) {
        suspended.executeWithoutResult(status -> {
            var chunk=chunks.selectById(chunkId);
            if (chunk==null) throw new IllegalArgumentException("知识片段不存在");
            var file=files.selectOne(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId,chunk.getFileId()));
            if (file==null || !Objects.equals(file.getKnowledgeBaseId(),chunk.getKnowledgeBaseId())) {
                throw new PipelineException("INDEX_EXECUTION",chunk.getFileId(),"原文件已不存在或归属已变化");
            }
            var kb=bases.requireById(chunk.getKnowledgeBaseId());
            var context=context(kb,chunk.getFileId(),file.getFileName(),List.of(chunk.getContent()),"REEMBED");
            context.setIndexTarget(DocumentIndexTargetSnapshot.from(file,chunk));
            context.setVectors(List.of(embedding.embed(requireEmbeddingModelInstanceId(kb),chunk.getContent())));
            writeAndPublish(context,()->executions.publishReplacement(context));
        });
    }

    private void writeAndPublish(PipelineContext context,Runnable publish) {
        try {
            vectors.process(context);
            publication.executeWithoutResult(status -> publish.run());
        } catch (RuntimeException failure) {
            try { executions.abandonStandalone(context); }
            catch (RuntimeException cleanupFailure) {
                // A publication commit may have succeeded despite a lost response; never delete from memory.
                log.warn("索引执行状态暂未收敛，将按持久化截止时间恢复: execution={}, errorType={}",
                        context.getIndexExecutionId(),cleanupFailure.getClass().getSimpleName());
            }
            throw failure;
        }
    }

    private PipelineContext context(KnowledgeBase kb,String fileId,String fileName,List<String> content,String operation) {
        if (fileId==null || fileId.isBlank() || fileId.length()>128 || fileId.indexOf('\0')>=0) throw new IllegalArgumentException("文件ID无效");
        if (content==null || content.isEmpty() || content.stream().anyMatch(text->text==null || text.isBlank())) throw new IllegalArgumentException("知识片段正文不能为空");
        var context=new PipelineContext();
        context.setKnowledgeBaseId(kb.getId()); context.setKnowledgeBaseCode(kb.getCode());
        context.setVectorCollectionName(requireVectorCollectionName(kb)); context.setKnowledgeBaseDimension(kb.getDimension());
        context.setFileId(fileId); context.setFileName(fileName); context.setChunks(List.copyOf(content));
        context.setIndexOperation(operation); context.setIndexExecutionId(UUID.randomUUID().toString());
        return context;
    }
}
