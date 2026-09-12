package com.enterprise.ai.support;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

/** A real transaction manager for tests whose repositories are explicit substitutes. */
public final class ArtifactLifecycleTestSupport {
    private ArtifactLifecycleTestSupport() { }

    public static DataSourceTransactionManager transactions() {
        return new DataSourceTransactionManager(new DriverManagerDataSource(
                "jdbc:h2:mem:artifact_tx_" + UUID.randomUUID(), "sa", ""));
    }

    public static org.mybatis.spring.SqlSessionTemplate session(javax.sql.DataSource source,
                                                                Class<?>... mapperTypes) throws Exception {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var global = com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults()
                .setMetaObjectHandler(new com.enterprise.ai.config.MyBatisPlusConfig().metaObjectHandler());
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(configuration, global);
        for (var mapper : mapperTypes) configuration.addMapper(mapper);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        return new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
    }
}
