package com.enterprise.ai.runtime.execution.trace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkflowTraceSanitizerSourceConstraintTest {

    @Test
    void productionSourceDoesNotContainTestSensitiveMarkers() throws Exception {
        Path source = Paths.get("src/main/java/com/enterprise/ai/runtime/trace/WorkflowTraceSanitizer.java");
        String content = Files.readString(source, StandardCharsets.UTF_8);

        for (String forbidden : List.of(
                "secret-" + "marker",
                "response-body-" + "marker",
                "query-" + "marker",
                "knowledge-content-" + "marker")) {
            assertFalse(content.contains(forbidden), () -> "Production source contains " + forbidden);
        }
    }
}
