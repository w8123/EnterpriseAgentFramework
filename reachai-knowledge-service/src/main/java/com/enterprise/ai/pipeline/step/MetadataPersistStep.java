package com.enterprise.ai.pipeline.step;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.PipelineStep;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 步骤七：元数据持久化 — 将文件信息和 chunk 记录写入 MySQL。
 *
 * <p>保存：
 * <ul>
 *   <li>file_info 记录（文件元信息、chunk 数量、处理状态）</li>
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void process(PipelineContext context) {
        String kbCode = context.getKnowledgeBaseCode();
        KnowledgeBase kb = knowledgeBaseRepository.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, kbCode));
        if (kb == null) {
            throw new PipelineException(getName(), context.getFileId(), "知识库不存在: " + kbCode);
        }

        List<String> chunks = context.getChunks();
        List<String> vectorIds = context.getVectorIds();

        // 保存文件记录（含文件大小和原始文本，支持后续重新解析）
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileId(context.getFileId());
        fileInfo.setKnowledgeBaseId(kb.getId());
        fileInfo.setFileName(context.getFileName());
        fileInfo.setChunkCount(chunks.size());
        fileInfo.setStatus(1);
        if (context.getFile() != null) {
            fileInfo.setFileSize(context.getFile().getSize());
            String suffix = context.getFileName() != null
                    ? context.getFileName().substring(context.getFileName().lastIndexOf('.') + 1).toLowerCase()
                    : "";
            fileInfo.setFileType(suffix);
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
            chunk.setCollectionName(kbCode);
            chunkRepository.insert(chunk);
        }

        log.debug("MetadataPersistStep 完成: fileId={}, 知识库={}, chunk数={}",
                context.getFileId(), kbCode, chunks.size());
    }

    @Override
    public String getName() {
        return "METADATA_PERSIST";
    }
}
