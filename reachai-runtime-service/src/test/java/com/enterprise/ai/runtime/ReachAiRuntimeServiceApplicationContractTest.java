package com.enterprise.ai.runtime;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReachAiRuntimeServiceApplicationContractTest {

    @Test
    void scansOnlyRuntimeFeignClientsDuringShellPhase() {
        EnableFeignClients enableFeignClients =
                ReachAiRuntimeServiceApplication.class.getAnnotation(EnableFeignClients.class);

        assertArrayEquals(new String[] {"com.enterprise.ai.runtime.client"}, enableFeignClients.basePackages());
    }

    @Test
    void scansOnlyAnnotatedRuntimeMappersDuringShellPhase() {
        MapperScan mapperScan = ReachAiRuntimeServiceApplication.class.getAnnotation(MapperScan.class);

        assertArrayEquals(new String[] {
                "com.enterprise.ai.runtime.credential",
                "com.enterprise.ai.runtime.debug",
                "com.enterprise.ai.runtime.execution",
                "com.enterprise.ai.runtime.interaction",
                "com.enterprise.ai.runtime.eval",
                "com.enterprise.ai.runtime.runops",
                "com.enterprise.ai.runtime.trace",
                "com.enterprise.ai.runtime.workflow"
        }, mapperScan.value());
        assertEquals(Mapper.class, mapperScan.annotationClass());
    }
}
