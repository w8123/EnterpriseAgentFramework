package com.enterprise.ai.capability;

import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
                "com.enterprise.ai.agent.capability.catalog.semantic",
                "com.enterprise.ai.agent.capability.catalog.domain",
                "com.enterprise.ai.agent.capability.catalog.graph",
                "com.enterprise.ai.agent.capability.catalog.scan",
                "com.enterprise.ai.agent.capability.catalog.tool.definition",
                "com.enterprise.ai.capability.catalog.composition",
                "com.enterprise.ai.capability.catalog.mining",
                "com.enterprise.ai.capability.catalog.retrieval"
        }, mapperScan.value());
        assertEquals(Mapper.class, mapperScan.annotationClass());
    }
}
