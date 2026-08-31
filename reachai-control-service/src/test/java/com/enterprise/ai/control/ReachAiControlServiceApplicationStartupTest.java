package com.enterprise.ai.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.lazy-initialization=false",
                "spring.datasource.url=jdbc:h2:mem:control_startup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "eaf.embed-token.secret=test-only-control-startup-key",
                "reachai.ai-coding-task.secret-pepper=test-only-control-startup-pepper",
                "reachai.auth.local.bootstrap-admin.enabled=false",
                "reachai.skill.builtin-bootstrap-enabled=false",
                "reachai.context.personal-memory.outbox-enabled=false",
                "reachai.context.personal-memory.lifecycle-enabled=false",
                "services.capability-service.url=http://localhost:18605",
                "services.runtime-service.url=http://localhost:18602"
        })
class ReachAiControlServiceApplicationStartupTest {

    @Test
    void applicationContextStarts() {
    }
}
