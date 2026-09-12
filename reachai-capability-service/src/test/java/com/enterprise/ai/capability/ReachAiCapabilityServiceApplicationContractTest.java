package com.enterprise.ai.capability;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.enterprise.ai.capability.registry.CapabilitySourceStateMapper;
import com.enterprise.ai.capability.registry.CapabilitySyncReceiptMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScannerRegistrar;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReachAiCapabilityServiceApplicationContractTest {

    @Test
    void scansCapabilityCatalogAndRegistryPackagesDuringPhysicalSplit() {
        SpringBootApplication springBootApplication =
                ReachAiCapabilityServiceApplication.class.getAnnotation(SpringBootApplication.class);

        assertArrayEquals(new String[] {
                "com.enterprise.ai.capability",
                "com.enterprise.ai.agent.registry",
                "com.enterprise.ai.agent.capability"
        }, springBootApplication.scanBasePackages());
    }

    @Test
    void scansCapabilityOwnedRegistryMappers() {
        MapperScan mapperScan = ReachAiCapabilityServiceApplication.class.getAnnotation(MapperScan.class);

        assertArrayEquals(new String[] {
                "com.enterprise.ai.agent.registry",
                "com.enterprise.ai.agent.capability",
                "com.enterprise.ai.capability.registry",
                "com.enterprise.ai.capability.catalog.retrieval",
                "com.enterprise.ai.capability.externalapi"
        }, mapperScan.value());
        assertEquals(Mapper.class, mapperScan.annotationClass());

        for (String parent : mapperScan.value()) {
            for (String candidate : mapperScan.value()) {
                if (!parent.equals(candidate)) {
                    assertFalse(candidate.startsWith(parent + "."),
                            () -> "MapperScan roots must not overlap: " + parent + " and " + candidate);
                }
            }
        }
    }

    @Test
    void registersSourceStateAndSyncReceiptMappersThroughApplicationConfiguration() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("mapper-registration", new JdbcTransactionFactory(),
                new DriverManagerDataSource("jdbc:h2:mem:capability_mapper_registration")));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SqlSessionFactory.class,
                    () -> new MybatisSqlSessionFactoryBuilder().build(configuration));
            new MapperScannerRegistrar().registerBeanDefinitions(
                    AnnotationMetadata.introspect(ReachAiCapabilityServiceApplication.class), context);
            context.refresh();

            assertNotNull(context.getBean(CapabilitySourceStateMapper.class));
            assertNotNull(context.getBean(CapabilitySyncReceiptMapper.class));
        }
    }
}
