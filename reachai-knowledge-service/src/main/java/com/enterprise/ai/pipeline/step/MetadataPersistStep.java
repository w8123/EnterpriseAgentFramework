package com.enterprise.ai.pipeline.step;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.pipeline.document.DocumentChunkSource;
import com.enterprise.ai.pipeline.document.DocumentParseResult;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireVectorCollectionName;

/**
 * 步骤七：元数据持久化 — 将文件信息和 chunk 记录写入 MySQL。
 *
 * <p>保存：
 * <ul>
 *   <li>knowledge_file_info 记录（文件元信息、chunk 数量、处理状态）</li>
 *   <li>chunk 记录（文本内容、向量 ID、所属 collection）</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataPersistStep implements PipelineStep {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileInfoRepository fileInfoRepository;
    private final ChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;
    private final com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard publicationGuard;
    private final com.enterprise.ai.service.impl.KnowledgeTagService tags;
    private final com.enterprise.ai.service.impl.KnowledgeQuestionService questions;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void process(PipelineContext context) {
        String kbCode = context.getKnowledgeBaseCode();
        KnowledgeBase kb = context.getKnowledgeBaseId() == null ? null
                : knowledgeBaseRepository.selectById(context.getKnowledgeBaseId());
        if (kb == null || !Objects.equals(kb.getCode(), kbCode)
                || !Objects.equals(requireVectorCollectionName(kb), context.getVectorCollectionName())) {
            throw new PipelineException(getName(), context.getFileId(), "导入知识库身份已失效: " + kbCode);
        }
        publicationGuard.lockOwnedExecution(context, kb.getId());

        List<String> chunks = context.getChunks();
        List<String> vectorIds = context.getVectorIds();

        // The vector step is an external side effect and runs before this database
        // transaction.  Replace metadata for the same immutable import attempt so
        // a retry after "vectors written, metadata/complete marker failed" is
        // idempotent.  A different job may never take over an existing fileId.
        FileInfo existingFile = fileInfoRepository.selectOne(
                new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, context.getFileId()).last("FOR UPDATE"));
        if (existingFile != null && (context.getImportJobId() == null
                || !Objects.equals(existingFile.getKnowledgeBaseId(), kb.getId())
                || !Objects.equals(existingFile.getImportJobId(), context.getImportJobId()))) {
            throw new PipelineException(getName(), context.getFileId(),
                    "fileId 已被其他知识库或导入任务占用: " + context.getFileId());
        }
        questions.unlinkFileChunks(kb.getId(), context.getFileId());
        tags.retireFile(kb.getId(), context.getFileId());
        chunkRepository.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getFileId, context.getFileId())
                .eq(Chunk::getKnowledgeBaseId, kb.getId()));
        fileInfoRepository.delete(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, context.getFileId())
                .eq(FileInfo::getKnowledgeBaseId, kb.getId()));

        // 保存文件记录（含文件大小和原始文本，支持后续重新解析）
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileId(context.getFileId());
        fileInfo.setKnowledgeBaseId(kb.getId());
        fileInfo.setFileName(context.getFileName());
        fileInfo.setChunkCount(chunks.size());
        fileInfo.setStatus(1);
        fileInfo.setFileSize(context.getSourceFileSize() != null ? context.getSourceFileSize()
                : context.getFile() != null ? context.getFile().getSize() : 0L);
        DocumentParseResult parsedDocument = context.getParsedDocument();
        String suffix = parsedDocument != null && parsedDocument.getFormat() != null
                ? parsedDocument.getFormat().getExtension()
                : extractSuffix(context.getFileName());
        fileInfo.setFileType(suffix);
        fileInfo.setSourceObjectKey(context.getSourceObjectKey());
        fileInfo.setSourceContentType(context.getSourceContentType());
        fileInfo.setSourceSha256(context.getSourceSha256());
        fileInfo.setParseArtifactObjectKey(context.getParseArtifactObjectKey());
        fileInfo.setImportJobId(context.getImportJobId());
        if (parsedDocument != null) {
            fileInfo.setParseProvider(parsedDocument.getProviderType() == null ? null
                    : parsedDocument.getProviderType().name());
            fileInfo.setParseProviderVersion(parsedDocument.getProviderVersion());
        }
        // 保存清洗后文本，用于重新解析
        String textToStore = context.getCleanedText() != null ? context.getCleanedText() : context.getRawText();
        fileInfo.setRawText(textToStore);
        fileInfoRepository.insert(fileInfo);

        // 批量保存 chunk 记录
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = new Chunk();
            chunk.setFileId(context.getFileId());
            chunk.setKnowledgeBaseId(kb.getId());
            chunk.setContent(chunks.get(i));
            chunk.setChunkIndex(i);
            chunk.setVectorId(i < vectorIds.size() ? vectorIds.get(i) : null);
            chunk.setCollectionName(context.getVectorCollectionName());
            chunk.setHitCount(0);
            chunk.setEnabled(1);
            DocumentChunkSource source = i < context.getChunkSources().size()
                    ? context.getChunkSources().get(i) : null;
            if (source != null) {
                chunk.setElementType(source.getElementType());
                chunk.setSectionPath(source.getSectionPath());
                chunk.setSourceLocatorJson(writeSourceLocator(source));
            }
            chunkRepository.insert(chunk);
        }

        publicationGuard.completeInMetadataTransaction(context);
        log.debug("MetadataPersistStep 完成: fileId={}, 知识库={}, chunk数={}",
                context.getFileId(), kbCode, chunks.size());
    }

    @Override
    public String getName() {
        return "METADATA_PERSIST";
    }

    private String writeSourceLocator(DocumentChunkSource source) {
        if (source.getSourceLocator() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(source.getSourceLocator());
        } catch (Exception e) {
            throw new PipelineException(getName(), null, "无法保存 Chunk 来源定位", e);
        }
    }

    private static String extractSuffix(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot + 1 < fileName.length() ? fileName.substring(dot + 1).toLowerCase() : "";
    }
}
