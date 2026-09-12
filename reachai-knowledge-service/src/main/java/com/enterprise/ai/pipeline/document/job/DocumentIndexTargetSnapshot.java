package com.enterprise.ai.pipeline.document.job;

import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Server-resolved chunk generation; never reconstruct it from a caller-supplied file ID. */
public record DocumentIndexTargetSnapshot(Long fileRowId, String fileGeneration, Long chunkId, String vectorId,
        String collectionName, String contentHash, String content) {
    public static DocumentIndexTargetSnapshot from(FileInfo file, Chunk chunk) {
        return new DocumentIndexTargetSnapshot(file.getId(), file.requireRecordGeneration(), chunk.getId(), chunk.getVectorId(),
                chunk.getCollectionName(), hash(chunk.getContent()), chunk.getContent());
    }

    public static String hash(String content) {
        if (content == null) throw new IllegalArgumentException("Chunk content is required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
