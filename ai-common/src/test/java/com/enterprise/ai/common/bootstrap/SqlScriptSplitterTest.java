package com.enterprise.ai.common.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptSplitterTest {

    @Test
    void keepsSemicolonsInsideStringsCommentsAndProcedureBodies() {
        String script = """
                -- leading comment; not a statement
                CREATE TABLE t (`id` INT COMMENT '主键；含 ; 分号') COMMENT='a;b';
                # hash comment;
                /* block ; comment */ INSERT INTO t VALUES (1);
                DELIMITER $$
                CREATE PROCEDURE p()
                BEGIN
                    SELECT 1;
                    SELECT 'x'';y';
                END$$
                DELIMITER ;
                SELECT "a;b" ;
                """;

        List<String> statements = SqlScriptSplitter.split(script);

        assertEquals(4, statements.size(), statements.toString());
        assertTrue(statements.get(0).contains("'主键；含 ; 分号'") && statements.get(0).endsWith("COMMENT='a;b'"));
        assertTrue(statements.get(1).endsWith("INSERT INTO t VALUES (1)"));
        assertTrue(statements.get(2).startsWith("CREATE PROCEDURE p()") && statements.get(2).endsWith("END"));
        assertTrue(statements.get(2).contains("SELECT 1;"));
        assertEquals("SELECT \"a;b\"", statements.get(3));
    }

    @Test
    void splitsTheRepositoryBaselineIntoExecutableStatements() throws Exception {
        String baseline = Files.readString(Path.of("..", "sql", "initV2.sql"), StandardCharsets.UTF_8);

        List<String> statements = SqlScriptSplitter.split(baseline);

        assertTrue(statements.size() > 300, "statements: " + statements.size());
        assertTrue(statements.stream().noneMatch(sql -> sql.toUpperCase().startsWith("DELIMITER")));
        assertTrue(statements.stream().filter(sql -> sql.startsWith("CREATE PROCEDURE")).count() >= 3);
        assertTrue(statements.stream().allMatch(sql -> !sql.isBlank()));
    }
}
