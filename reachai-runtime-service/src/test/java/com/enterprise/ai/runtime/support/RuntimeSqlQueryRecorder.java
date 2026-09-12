package com.enterprise.ai.runtime.support;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 记录实际 JDBC 查询返回的行数，不记录绑定参数。用于 H2 查询规模回归。 */
@Intercepts(@Signature(type = StatementHandler.class, method = "query",
        args = {Statement.class, ResultHandler.class}))
public final class RuntimeSqlQueryRecorder implements Interceptor {

    private final List<Query> queries = new ArrayList<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        String sql = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql();
        queries.add(new Query(sql, ((List<?>) result).size()));
        return result;
    }

    public List<Query> queries() {
        return List.copyOf(queries);
    }

    public void clear() {
        queries.clear();
    }

    public record Query(String sql, int rowCount) {
    }
}
