package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class AiCodingReusableTaskReaderTest {
    JdbcTemplate jdbc;
    AiCodingReusableTaskReader reader;
    int sequence;

    @BeforeEach void database() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:task_reuse_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : List.of("control_ai_coding_task", "control_ai_coding_task_target")) {
            var match = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\(.*?\\)\\s*ENGINE=.*?;").matcher(baseline);
            assertTrue(match.find());
            jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");"));
        }
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AiCodingTaskMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        reader = new AiCodingReusableTaskReader(new SqlSessionTemplate(factory.getObject()).getMapper(AiCodingTaskMapper.class));
    }

    @Test void selectsLatestReusablePrimaryTargetAndKeepsProviderAndTaskKindScope() {
        task("old", "CANDIDATE", "CODEX", "READY", "PRIMARY");
        task("running", "CANDIDATE", "codex", "RUNNING", "PRIMARY");
        task("completed", "CANDIDATE", "CODEX", "COMPLETED", "PRIMARY");
        task("other-provider", "CANDIDATE", "CURSOR", "RUNNING", "PRIMARY");
        task("other-kind", "OTHER", "CODEX", "RUNNING", "PRIMARY");
        task("related", "CANDIDATE", "CODEX", "RUNNING", "RELATED");
        assertEquals("running", reader.latestForPrimaryTarget("CANDIDATE", " CODEX ", "TRACE", "trace-1").orElseThrow());
        assertTrue(reader.latestForPrimaryTarget("CANDIDATE", "CODEX", "TRACE", "trace-other").isEmpty());
        assertTrue(reader.latestForPrimaryTarget("CANDIDATE", "CODEX", "WORKFLOW", "trace-1").isEmpty());
    }

    @Test void finishedTaskAndMissingTaskDoNotBecomeReusableCandidates() {
        task("failed", "CANDIDATE", "CODEX", "FAILED", "PRIMARY");
        task("deleted", "CANDIDATE", "CODEX", "RUNNING", "PRIMARY");
        jdbc.update("DELETE FROM control_ai_coding_task WHERE task_id='deleted'");
        assertTrue(reader.latestForPrimaryTarget("CANDIDATE", "CODEX", "TRACE", "trace-1").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reader.latestForPrimaryTarget("CANDIDATE", "CODEX", "TRACE", ""));
    }

    private void task(String id, String kind, String provider, String status, String role) {
        jdbc.update("""
                INSERT INTO control_ai_coding_task
                  (task_id,project_id,project_code,capability_key,task_kind,executor_provider,title,objective,
                   access_mode,execution_status,result_contract_key,result_contract_version,context_snapshot_json)
                VALUES (?,7,'orders','TRACE_WORKFLOW',?,?,?,?,'READ_WRITE',?,'candidate','v1','{}')
                """, id, kind, provider, id, id, status);
        jdbc.update("""
                INSERT INTO control_ai_coding_task_target
                  (task_id,target_type,target_key,target_role,access_mode,created_at)
                VALUES (?,'TRACE','trace-1',?,'READ_WRITE',?)
                """, id, role, LocalDateTime.of(2026, 9, 5, 10, 0).plusSeconds(++sequence));
    }
}
