package com.enterprise.ai.pipeline.document.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DocumentArtifactIdentity {
    private DocumentArtifactIdentity() { }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static String storage(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("文档工件后端缺少稳定身份");
        }
        return value;
    }

    static String key(String key) {
        if (key == null || !key.startsWith("knowledge-document-import/") || key.length() > 768 || key.indexOf('\\') >= 0
                || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("无效的文档工件 key");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf(':') >= 0) {
                throw new IllegalArgumentException("文档工件 key 必须是无路径别名的相对路径");
            }
        }
        return key;
    }

    static String artifact(String storage, String key) {
        return hash(storage(storage) + "\u0000" + key(key));
    }
}
