package com.jishi.ai.agent.tools;

import com.jishi.ai.agent.rag.RagClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库搜索工具
 * <p>
 * 通过极视角平台的知识问答智能体检索企业制度、技术文档、操作规范等信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AiTool {

    private final RagClient ragClient;

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "搜索企业知识库。当用户询问公司制度、技术规范、操作流程、安全管理规定等知识类问题时使用。参数：query（搜索关键词或问题描述）。";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        log.info("[KnowledgeSearchTool] 检索: {}", query);
        return ragClient.retrieve(query, "tool_user");
    }
}
