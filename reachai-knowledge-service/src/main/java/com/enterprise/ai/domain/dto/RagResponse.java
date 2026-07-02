package com.enterprise.ai.domain.dto;

import com.enterprise.ai.domain.vo.SimilarItem;
import lombok.Data;

import java.util.List;

@Data
public class RagResponse {

    /** LLM 生成的回答 */
    private String answer;

    /** 引用的上下文片段（供前端展示来源） */
    private List<SimilarItem> references;
}
