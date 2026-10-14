package com.enterprise.ai.control.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.governance.ControlToolAclController;
import com.enterprise.ai.control.governance.ControlToolAclMapper;
import lombok.Data;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Exercises the production interceptor against a database, including count and LIMIT. */
class ControlMybatisPlusPaginationPersistenceTest {
    private SqlSession session;
    private ControlToolAclController controller;

    @BeforeEach
    void prepareDatabase() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:control_pagination_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_tool_acl (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, role_code VARCHAR(64),
                  project_id BIGINT, project_code VARCHAR(96), target_kind VARCHAR(16),
                  target_name VARCHAR(128), permission VARCHAR(16), note VARCHAR(128),
                  enabled BOOLEAN, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO control_tool_acl (role_code,target_kind,target_name,permission,enabled)
                VALUES ('PLATFORM_ADMIN','TOOL','project:read-a','ALLOW',true),
                       ('VIEWER','TOOL','project:read-b','ALLOW',true),
                       ('PLATFORM_ADMIN','TOOL','project:read-c','ALLOW',true),
                       ('PLATFORM_ADMIN','ALL','*','DENY',true)
                """);
        jdbc.execute("CREATE TABLE optimistic_probe (id BIGINT PRIMARY KEY, label VARCHAR(64), version INT)");
        jdbc.update("INSERT INTO optimistic_probe (id,label,version) VALUES (1,'initial',0)");

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ControlToolAclMapper.class);
        configuration.addMapper(VersionedProbeMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(new ControlMybatisPlusConfiguration().controlMybatisPlusInterceptor());
        session = factory.getObject().openSession(true);
        controller = new ControlToolAclController(session.getMapper(ControlToolAclMapper.class), null);
    }

    @AfterEach
    void closeSession() {
        if (session != null) session.close();
    }

    @Test
    void aclPagesCountFilteredRowsAndLimitEachPage() {
        var first = controller.page(1, 1, "PLATFORM_ADMIN", "tool").getBody();
        assertNotNull(first);
        assertEquals(2, first.getTotal());
        assertEquals(2, first.getPages());
        assertEquals(1, first.getRecords().size());
        assertEquals(3L, first.getRecords().get(0).getId());

        var second = controller.page(2, 1, "PLATFORM_ADMIN", "TOOL").getBody();
        assertNotNull(second);
        assertEquals(2, second.getTotal());
        assertEquals(1, second.getRecords().size());
        assertEquals(1L, second.getRecords().get(0).getId());

        var beyond = controller.page(3, 1, "PLATFORM_ADMIN", "TOOL").getBody();
        assertNotNull(beyond);
        assertEquals(2, beyond.getTotal());
        assertEquals(0, beyond.getRecords().size());

        var all = controller.page(1, 1, null, null).getBody();
        assertNotNull(all);
        assertEquals(4, all.getTotal());
        assertEquals(4, all.getPages());
        assertEquals(1, all.getRecords().size());
    }

    @Test
    void addingPaginationPreservesStaleUpdateProtection() {
        var mapper = session.getMapper(VersionedProbeMapper.class);
        var current = mapper.selectById(1L);
        session.clearCache(); // A second writer must hold its own snapshot, not the first-level cached object.
        var stale = mapper.selectById(1L);
        assertNotSame(current, stale);
        current.setLabel("saved");
        assertEquals(1, mapper.updateById(current));
        assertEquals(1, current.getVersion());
        stale.setLabel("late-overwrite");
        assertEquals(0, mapper.updateById(stale));
        assertEquals("saved", mapper.selectById(1L).getLabel());
        assertEquals(1, mapper.selectById(1L).getVersion());
    }

    public interface VersionedProbeMapper extends BaseMapper<VersionedProbe> { }

    @Data
    @TableName("optimistic_probe")
    public static class VersionedProbe {
        @TableId(type = IdType.INPUT)
        private Long id;
        private String label;
        @Version
        private Integer version;
    }
}
