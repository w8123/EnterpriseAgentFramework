package com.enterprise.ai.common.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a {@code KEY=VALUE} file in the format Docker Compose and shell scripts share:
 * blank lines and {@code #} comments are ignored, an optional {@code export } prefix is
 * dropped, and a value may be wrapped in single or double quotes.
 */
final class EnvFile {

    private EnvFile() {
    }

    static Map<String, Object> read(Path file) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("﻿")) {
                line = line.substring(1);
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            } else {
                int comment = value.indexOf(" #");
                if (comment >= 0) {
                    value = value.substring(0, comment).strip();
                }
            }
            if (!value.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }
}
