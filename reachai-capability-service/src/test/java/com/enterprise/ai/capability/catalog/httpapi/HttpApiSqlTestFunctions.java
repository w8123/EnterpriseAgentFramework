package com.enterprise.ai.capability.catalog.httpapi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** H2 equivalents of the MySQL functions used by current source integrity filtering. */
public final class HttpApiSqlTestFunctions {
    private HttpApiSqlTestFunctions() { }

    public static Integer jsonValid(String value) {
        if (value == null) return null;
        return parse(value) == null ? 0 : 1;
    }

    public static String jsonType(String value) {
        JsonNode node = parse(value);
        if (node == null) throw new IllegalArgumentException("invalid JSON");
        return node.isObject() ? "OBJECT" : node.isArray() ? "ARRAY" : "SCALAR";
    }

    public static String sha2(String value, Integer bits) throws Exception {
        if (value == null) return null;
        if (bits == null || bits != 256) throw new IllegalArgumentException("only SHA-256 is used");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonNode parse(String value) {
        try { return new ObjectMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value); }
        catch (Exception invalid) { return null; }
    }
}
