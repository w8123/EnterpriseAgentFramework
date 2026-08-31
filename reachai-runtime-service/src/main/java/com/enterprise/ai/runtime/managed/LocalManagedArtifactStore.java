package com.enterprise.ai.runtime.managed;

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

/** Single-node development storage. Production workloads use the S3 implementation. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.runtime.managed-executor.artifact-store", name = "type",
        havingValue = "local")
public class LocalManagedArtifactStore implements ManagedArtifactStore {

    private final ManagedArtifactStoreProperties properties;
    private Path root;

    @PostConstruct
    void initialize() {
        try {
            root = Path.of(properties.getLocalRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException | RuntimeException failure) {
            throw new ManagedArtifactStoreException("Cannot initialize Managed Executor local artifact store", failure);
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String mediaType) {
        if (contentLength < 0) throw new ManagedArtifactStoreException("Artifact content length is required");
        Path target = resolve(objectKey);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(temporary) != contentLength) {
                throw new ManagedArtifactStoreException("Artifact content length changed during upload");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (ManagedArtifactStoreException failure) {
            deleteQuietly(temporary);
            throw failure;
        } catch (IOException failure) {
            deleteQuietly(temporary);
            throw new ManagedArtifactStoreException("Cannot write Managed Executor artifact", failure);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException failure) {
            throw new ManagedArtifactStoreException("Cannot read Managed Executor artifact", failure);
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException failure) {
            throw new ManagedArtifactStoreException("Cannot delete Managed Executor artifact", failure);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("\\")) {
            throw new ManagedArtifactStoreException("Managed Executor artifact key is invalid");
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new ManagedArtifactStoreException("Managed Executor artifact key is outside the store root");
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the write failure.
        }
    }
}
