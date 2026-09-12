package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** Knowledge-owned catalog queries shared by management and retrieval, with no dependency on either facade. */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseLookup {
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBase requireById(Long id) {
        KnowledgeBase kb = id == null ? null : knowledgeBaseRepository.selectById(id);
        if (kb == null) throw new IllegalArgumentException("知识库不存在: " + id);
        return kb;
    }

    public KnowledgeBase requireByCode(String code) {
        KnowledgeBase kb = knowledgeBaseRepository.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, code));
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: " + code);
        }
        return kb;
    }


    public List<KnowledgeBase> resolveActive(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return knowledgeBaseRepository.selectList(
                    new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getStatus, 1));
        }
        return knowledgeBaseRepository.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .in(KnowledgeBase::getCode, codes)
                        .eq(KnowledgeBase::getStatus, 1));
    }


}
