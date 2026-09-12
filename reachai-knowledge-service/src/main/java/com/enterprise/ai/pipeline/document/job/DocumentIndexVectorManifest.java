package com.enterprise.ai.pipeline.document.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

/** Immutable, compact vector identity; reconstructs bounded cleanup batches after restart. */
public record DocumentIndexVectorManifest(String prefix, int count, String singleVectorId) {
    public DocumentIndexVectorManifest(String prefix, int count) { this(prefix, count, null); }

    public DocumentIndexVectorManifest {
        boolean generated = prefix != null && prefix.matches("[0-9a-f]{64}") && singleVectorId == null && count > 0;
        boolean exact = prefix == null && singleVectorId != null && !singleVectorId.isBlank()
                && singleVectorId.length() <= 256 && singleVectorId.indexOf('\0') < 0 && count == 1;
        if (!generated && !exact) {
            throw new IllegalArgumentException("Invalid indexing vector manifest");
        }
    }

    public static DocumentIndexVectorManifest forExecution(String fileId, String leaseOwner, int count) {
        if (fileId == null || fileId.isBlank() || leaseOwner == null || leaseOwner.isBlank()
                || fileId.indexOf('\0') >= 0 || leaseOwner.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("File and execution lease identity are required");
        }
        try {
            String identity = fileId + "\u0000" + leaseOwner;
            String prefix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
            return new DocumentIndexVectorManifest(prefix, count);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String vectorId(int index) {
        if (index < 0 || index >= count) throw new IllegalArgumentException("Vector index outside manifest");
        return singleVectorId != null ? singleVectorId : prefix + "_chunk_" + index;
    }

    public List<String> batch(int cursor, int limit) {
        if (cursor < 0 || cursor > count || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Invalid cleanup batch bounds");
        }
        int end = cursor + Math.min(count - cursor, limit);
        return IntStream.range(cursor, end).mapToObj(this::vectorId).toList();
    }
}
