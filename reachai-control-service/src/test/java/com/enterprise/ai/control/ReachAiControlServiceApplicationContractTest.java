package com.enterprise.ai.control;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.EnableFeignClients;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ReachAiControlServiceApplicationContractTest {

    @Test
    void scansOnlyControlFeignClientsDuringShellPhase() {
        EnableFeignClients enableFeignClients =
                ReachAiControlServiceApplication.class.getAnnotation(EnableFeignClients.class);

        assertArrayEquals(new String[] {"com.enterprise.ai.control.client"}, enableFeignClients.basePackages());
    }
}
