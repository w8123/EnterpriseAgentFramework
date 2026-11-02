package com.enterprise.ai.common.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReachAiHomeTest {

    @Test
    void homeDefaultsToTheConfigFileDirectorySoDevScriptAndIdeShareSecrets(@TempDir Path checkout) throws Exception {
        Path config = Files.writeString(checkout.resolve("reachai.env"), "AI_MYSQL_PASSWORD=pw\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("REACHAI_CONFIG_FILE", config.toString());

        assertEquals(checkout.resolve(".reachai").resolve("secrets.properties"),
                ReachAiHome.secretsFile(environment));
    }

    @Test
    void explicitHomeWins(@TempDir Path checkout) throws Exception {
        Path config = Files.writeString(checkout.resolve("reachai.env"), "");
        Path home = checkout.resolve("data");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("REACHAI_CONFIG_FILE", config.toString())
                .withProperty("REACHAI_HOME", home.toString());

        assertEquals(home.resolve("secrets.properties"), ReachAiHome.secretsFile(environment));
    }
}
