package com.enterprise.ai.pipeline.document.artifact;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Single-node development implementation. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.knowledge.document-artifact-store", name = "type",
        havingValue = "local", matchIfMissing = true)
public class LocalDocumentArtifactStore implements DocumentArtifactBackend {

    private final DocumentArtifactProperties properties;
    private Path root;
    private String storageId;

    @PostConstruct
    void initialize() {
        try {
            root = Path.of(properties.getLocalRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            root = root.toRealPath();
            storageId = localIdentity(root);
        } catch (IOException e) {
            throw new DocumentArtifactException("无法初始化本地文档工件目录", e);
        }
    }

    @Override
    public String storageId() {
        return storageId;
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String contentType) {
        Path target = resolve(objectKey);
        Path temporary = staging(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.createDirectories(temporary.getParent());
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
            Files.deleteIfExists(staging(objectKey));
        } catch (IOException e) {
            throw new DocumentArtifactException("无法删除文档工件: " + objectKey, e);
        }
    }

    private Path resolve(String objectKey) {
        Path resolved = root.resolve(DocumentArtifactIdentity.key(objectKey)).normalize();
        if (!resolved.startsWith(root)) {
            throw new DocumentArtifactException("非法文档工件 key");
        }
        return resolved;
    }

    private Path staging(String key) {
        return root.resolve(".reachai-upload").resolve(DocumentArtifactIdentity.hash(DocumentArtifactIdentity.key(key)) + ".part");
    }

    /** A copied checkout on another workstation must not claim this workstation's local artifacts. */
    private static synchronized String localIdentity(Path root) throws IOException {
        Path marker = root.resolve(".reachai-storage-id");
        try (var channel = FileChannel.open(marker, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (channel.size() == 0) {
                byte[] created = UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8);
                ByteBuffer bytes = ByteBuffer.wrap(created);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (channel.size() != 32) throw new IOException("本地工件后端身份损坏");
            ByteBuffer bytes = ByteBuffer.allocate(32);
            channel.position(0);
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) throw new IOException("本地工件后端身份不完整");
            }
            String identity = new String(bytes.array(), StandardCharsets.UTF_8);
            if (!identity.matches("[a-f0-9]{32}")) throw new IOException("本地工件后端身份无效");
            return DocumentArtifactIdentity.hash("local\u0000" + root + "\u0000" + identity);
        }
    }
}
