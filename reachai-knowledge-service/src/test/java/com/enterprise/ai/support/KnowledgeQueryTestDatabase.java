package com.enterprise.ai.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.config.MyBatisPlusConfig;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** 从基线中加载指定表和补列语句的 H2 查询夹具，不代表执行了完整 MySQL 迁移。 */
public final class KnowledgeQueryTestDatabase implements AutoCloseable {

    private final JdbcTemplate jdbc;
    private final SqlSessionTemplate session;
    private final boolean ownsEmbeddedDatabase;

    public KnowledgeQueryTestDatabase(List<String> tables, Class<?>... mapperTypes) throws Exception {
        ownsEmbeddedDatabase = true;
        var source = new DriverManagerDataSource("jdbc:h2:mem:knowledge_query_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : tables) {
            if (!table.matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid table name: " + table);
            // Column comments may contain semicolons; the table ends at its ENGINE clause.
            var definition = Pattern.compile("(?ism)CREATE TABLE IF NOT EXISTS `" + table
                    + "`\\s*\\(.*?^\\)\\s*ENGINE=[^\\r\\n]*;").matcher(baseline);
            if (!definition.find()) throw new IllegalArgumentException("Table missing from baseline: " + table);
            jdbc.execute(definition.group().replaceAll("(?im)^\\)\\s*ENGINE=[^\\r\\n]*;", ");")
                    .replaceAll("(?i)COLLATE \\w+", "")
                    .replaceAll("(?i)\\)\\s+STORED(?=\\s+COMMENT)", ")")
                    .replaceAll("(`[^`]+`)\\(\\d+\\)", "$1")
                    .replaceAll("(?i)KEY `([^`]+)`", "KEY `" + table + "_$1`"));
            var additions = Pattern.compile("CALL add_col_if_absent\\('" + table
                    + "', '([^']+)', '((?:''|[^'])*)'\\);").matcher(baseline);
            while (additions.find()) {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS `" + additions.group(1) + "` "
                        + additions.group(2).replace("''", "'").replaceAll("(?i) AFTER `[^`]+`", ""));
            }
        }
        session = openSession(source, mapperTypes);
    }

    /** Connect to an already isolated datasource; its caller owns schema creation and cleanup. */
    public KnowledgeQueryTestDatabase(DataSource source, Class<?>... mapperTypes) throws Exception {
        ownsEmbeddedDatabase = false;
        jdbc = new JdbcTemplate(source);
        session = openSession(source, mapperTypes);
    }

    private static SqlSessionTemplate openSession(DataSource source, Class<?>... mapperTypes) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var globalConfig = GlobalConfigUtils.defaults().setMetaObjectHandler(new MyBatisPlusConfig().metaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        for (Class<?> type : mapperTypes) configuration.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        return new SqlSessionTemplate(factory.getObject());
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public <T> T mapper(Class<T> mapperType) {
        return session.getMapper(mapperType);
    }

    public void addInterceptor(Interceptor interceptor) {
        session.getConfiguration().addInterceptor(interceptor);
    }

    @Override
    public void close() {
        if (ownsEmbeddedDatabase) jdbc.execute("SHUTDOWN");
    }
}
