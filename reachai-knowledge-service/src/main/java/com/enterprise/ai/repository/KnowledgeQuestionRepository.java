package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.KnowledgeQuestion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeQuestionRepository extends BaseMapper<KnowledgeQuestion> {
    @org.apache.ibatis.annotations.Update("""
            UPDATE knowledge_question SET chunk_id=NULL WHERE knowledge_base_id=#{knowledgeBaseId}
              AND chunk_id IN (SELECT id FROM knowledge_chunk WHERE knowledge_base_id=#{knowledgeBaseId} AND file_id=#{fileId})
            """)
    int unlinkFileChunks(@org.apache.ibatis.annotations.Param("knowledgeBaseId") Long knowledgeBaseId,
                         @org.apache.ibatis.annotations.Param("fileId") String fileId);
}
