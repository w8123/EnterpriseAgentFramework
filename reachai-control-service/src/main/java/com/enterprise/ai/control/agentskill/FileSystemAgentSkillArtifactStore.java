package com.enterprise.ai.control.agentskill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Pattern;

/** Local content-addressed store. The interface allows an object-store implementation later. */
@Component
public class FileSystemAgentSkillArtifactStore implements AgentSkillArtifactStore {

    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern ARTIFACT_KEY = Pattern.compile("^sha256/[a-f0-9]{2}/[a-f0-9]{64}\\.zip$");

    private final Path root;

    public FileSystemAgentSkillArtifactStore(
            @Value("${reachai.skill.artifact-root:${java.io.tmpdir}/reachai-control/skill-artifacts}") String root) {
        if (!StringUtils.hasText(root)) {
            throw new IllegalArgumentException("reachai.skill.artifact-root is required");
        }
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredArtifact put(String expectedSha256, byte[] archive) {
        String digest = normalizeDigest(expectedSha256);
        if (archive == null || archive.length == 0) {
            throw AgentSkillException.invalidPackage("Skill artifact bytes are required");
        }
        String actual = AgentSkillPackageInspector.sha256(archive);
        if (!digest.equals(actual)) {
            throw AgentSkillException.invalidPackage("Skill artifact digest does not match the inspected package");
        }
        String key = key(digest);
        Path target = resolveKey(key);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                temporary = Files.createTempFile(target.getParent(), digest + "-", ".tmp");
                Files.write(temporary, archive);
                moveIntoPlaceOrConverge(temporary, target, digest);
                if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) temporary = null;
            }
            byte[] stored = readAndVerify(target, digest);
            return new StoredArtifact(key, digest, stored.length);
        } catch (IOException exception) {
            throw AgentSkillException.artifactFailure("Skill artifact could not be stored", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A failed best-effort temp cleanup must not hide the storage error.
                }
            }
        }
    }

    @Override
    public byte[] get(String artifactKey, String expectedSha256) {
        String digest = normalizeDigest(expectedSha256);
        Path target = resolveKey(artifactKey);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw AgentSkillException.notFound("Skill artifact is missing: " + artifactKey);
            }
            return readAndVerify(target, digest);
        } catch (AgentSkillException expected) {
            throw expected;
        } catch (IOException exception) {
            throw AgentSkillException.artifactFailure("Skill artifact could not be read", exception);
        }
    }

    Path root() {
        return root;
    }

    private byte[] readAndVerify(Path target, String expectedSha256) throws IOException {
        byte[] value = Files.readAllBytes(target);
        String actual = AgentSkillPackageInspector.sha256(value);
        if (!expectedSha256.equals(actual)) {
            throw new AgentSkillException("SKILL_ARTIFACT_DIGEST_MISMATCH",
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored Skill artifact failed its SHA-256 integrity check");
        }
        return value;
    }

    private void moveIntoPlaceOrConverge(Path temporary, Path target, String digest) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            try {
                Files.move(temporary, target);
            } catch (IOException concurrentOrStorageFailure) {
                acceptVerifiedConcurrentTargetOrThrow(target, digest, concurrentOrStorageFailure);
            }
        } catch (FileAlreadyExistsException concurrentWriterCompleted) {
            acceptVerifiedConcurrentTargetOrThrow(target, digest, concurrentWriterCompleted);
        } catch (IOException concurrentOrStorageFailure) {
            // Windows may report AccessDeniedException, rather than FileAlreadyExistsException,
            // when another writer wins a same-destination atomic move. Only converge when the
            // completed target exists and independently matches the requested content address.
            acceptVerifiedConcurrentTargetOrThrow(target, digest, concurrentOrStorageFailure);
        }
    }

    private void acceptVerifiedConcurrentTargetOrThrow(Path target, String digest, IOException moveFailure)
            throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw moveFailure;
        }
        try {
            readAndVerify(target, digest);
        } catch (IOException verificationFailure) {
            moveFailure.addSuppressed(verificationFailure);
            throw moveFailure;
        }
    }

    private Path resolveKey(String key) {
        if (!StringUtils.hasText(key) || !ARTIFACT_KEY.matcher(key).matches()) {
            throw AgentSkillException.invalidPackage("Skill artifact key is invalid");
        }
        Path target = root.resolve(key.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root)) {
            throw AgentSkillException.invalidPackage("Skill artifact key escapes the configured store");
        }
        return target;
    }

    private String normalizeDigest(String value) {
        String digest = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
        if (!SHA256.matcher(digest).matches()) {
            throw AgentSkillException.invalidPackage("Skill artifact SHA-256 is invalid");
        }
        return digest;
    }

    private String key(String digest) {
        return "sha256/" + digest.substring(0, 2) + "/" + digest + ".zip";
    }
}
