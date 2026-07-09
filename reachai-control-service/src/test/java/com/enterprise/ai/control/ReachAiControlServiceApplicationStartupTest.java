package com.enterprise.ai.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.lazy-initialization=false",
                "services.capability-service.url=http://localhost:18605",
                "services.runtime-service.url=http://localhost:18602"
        })
class ReachAiControlServiceApplicationStartupTest {

    @Test
    void applicationContextStarts() {
    }
}
