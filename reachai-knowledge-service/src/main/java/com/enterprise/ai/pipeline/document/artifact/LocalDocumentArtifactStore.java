package com.enterprise.ai.pipeline.document.artifact;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Single-node development implementation. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.knowledge.document-artifact-store", name = "type",
        havingValue = "local", matchIfMissing = true)
public class LocalDocumentArtifactStore implements DocumentArtifactStore {

    private final DocumentArtifactProperties properties;
    private Path root;

    @PostConstruct
    void initialize() {
        try {
            root = Path.of(properties.getLocalRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new DocumentArtifactException("无法初始化本地文档工件目录", e);
        }
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String contentType) {
        Path target = resolve(objectKey);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original failure as the externally visible one.
            }
            throw new DocumentArtifactException("无法写入文档工件: " + objectKey, e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException e) {
            throw new DocumentArtifactException("无法读取文档工件: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            throw new DocumentArtifactException("无法删除文档工件: " + objectKey, e);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new DocumentArtifactException("工件 key 不能为空");
        }
        Path resolved = root.resolve(objectKey.replace('\\', '/')).normalize();
        if (!resolved.startsWith(root)) {
            throw new DocumentArtifactException("非法文档工件 key");
        }
        return resolved;
    }
}
