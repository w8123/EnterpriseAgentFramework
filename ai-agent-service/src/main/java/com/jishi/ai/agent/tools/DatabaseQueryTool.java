package com.jishi.ai.agent.tools;

import com.jishi.ai.agent.client.BusinessSystemClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据库查询工具 — NL2SQL 场景的核心 Tool
 * <p>
 * 通过业务系统中间层执行查询，而非直连数据库。
 * 遵循 ARCHITECTURE.md 规范：LLM → Tool → 服务层 → 数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseQueryTool implements AiTool {

    private final BusinessSystemClient businessSystemClient;

    @Override
    public String name() {
        return "query_database";
    }

    @Override
    public String description() {
        return "执行数据库查询。当用户需要查询业务数据（班组得分、人员统计、月度报表等）时使用。输入参数 sql 为 SQL SELECT 语句，返回 JSON 格式查询结果。";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String sql = (String) args.get("sql");
        log.info("[DatabaseQueryTool] 执行查询: {}", sql);

        if (sql == null || !sql.trim().toUpperCase().startsWith("SELECT")) {
            return "{\"error\": \"安全限制：仅允许 SELECT 查询\"}";
        }

        return businessSystemClient.executeSqlQuery(sql);
    }
}
