package com.enterprise.ai.common.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EnvFileTest {

    @Test
    void readsComposeStyleEnvFiles(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("reachai.env");
        Files.writeString(file, """
                ﻿# 必填
                AI_MYSQL_PASSWORD=p@ss=word
                export AI_MYSQL_HOST = db.local
                QUOTED="a # not comment"
                SINGLE='x y'
                TRAILING=value # 说明
                EMPTY=
                not a pair
                """);

        Map<String, Object> values = EnvFile.read(file);

        assertEquals("p@ss=word", values.get("AI_MYSQL_PASSWORD"));
        assertEquals("db.local", values.get("AI_MYSQL_HOST"));
        assertEquals("a # not comment", values.get("QUOTED"));
        assertEquals("x y", values.get("SINGLE"));
        assertEquals("value", values.get("TRAILING"));
        assertFalse(values.containsKey("EMPTY"));
        assertEquals(5, values.size());
    }
}
