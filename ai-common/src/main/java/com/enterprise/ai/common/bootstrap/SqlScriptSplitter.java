package com.enterprise.ai.common.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a MySQL client script into statements the JDBC driver can execute one by one.
 * Understands {@code DELIMITER} lines, quoted strings, identifiers and comments, so a ';'
 * inside a Chinese COMMENT or a stored procedure body never ends a statement.
 */
final class SqlScriptSplitter {

    private SqlScriptSplitter() {
    }

    static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String delimiter = ";";
        int length = script.length();
        int i = 0;
        boolean lineStart = true;
        while (i < length) {
            if (lineStart) {
                int lineEnd = script.indexOf('\n', i);
                if (lineEnd < 0) {
                    lineEnd = length;
                }
                String line = script.substring(i, lineEnd).trim();
                if (line.toUpperCase(Locale.ROOT).startsWith("DELIMITER ") && current.toString().isBlank()) {
                    delimiter = line.substring("DELIMITER ".length()).trim();
                    i = lineEnd + 1;
                    continue;
                }
                lineStart = false;
            }
            char c = script.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(script, i, c);
                current.append(script, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < length && script.charAt(i + 1) == '-'
                    && (i + 2 >= length || Character.isWhitespace(script.charAt(i + 2)))) {
                i = skipToLineEnd(script, i);
                continue;
            }
            if (c == '#') {
                i = skipToLineEnd(script, i);
                continue;
            }
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                current.append(' ');
                continue;
            }
            if (script.startsWith(delimiter, i)) {
                addStatement(statements, current);
                i += delimiter.length();
                continue;
            }
            current.append(c);
            if (c == '\n') {
                lineStart = true;
            }
            i++;
        }
        addStatement(statements, current);
        return statements;
    }

    private static int skipQuoted(String script, int start, char quote) {
        int i = start + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < script.length() && script.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return script.length();
    }

    private static int skipToLineEnd(String script, int start) {
        int end = script.indexOf('\n', start);
        return end < 0 ? script.length() : end;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }
}
