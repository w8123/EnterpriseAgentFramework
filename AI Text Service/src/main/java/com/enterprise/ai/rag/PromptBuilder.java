package com.enterprise.ai.rag;

import com.enterprise.ai.domain.vo.SimilarItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * RAG Prompt 模板构建器。
 * <p>将检索到的上下文片段与用户问题组装为 LLM prompt。</p>
 */
@Component
public class PromptBuilder {

    private static final String TEMPLATE = """
            你是一个专业的知识库问答助手。请根据以下参考资料回答用户的问题。
            如果参考资料中没有相关信息，请如实告知用户。
            
            ## 参考资料
            %s
            
            ## 用户问题
            %s
            
            ## 回答要求
            1. 基于参考资料回答，不要编造信息
            2. 如果信息不充分，请说明
            3. 引用来源时标注文件名
            """;

    public String build(String question, List<SimilarItem> contexts) {
        String contextText = IntStream.range(0, contexts.size())
                .mapToObj(i -> {
                    SimilarItem item = contexts.get(i);
                    return String.format("[%d] 来源: %s\n%s", i + 1, item.getFileName(), item.getContent());
                })
                .collect(Collectors.joining("\n\n"));
        return String.format(TEMPLATE, contextText, question);
    }
}
