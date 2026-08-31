package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.client.control.RuntimeAgentSkillCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeAgentSkillCatalogClient.ExecutionReference;
import com.enterprise.ai.runtime.client.control.RuntimeAgentSkillCatalogClient.ExecutionResolution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Materializes exact Control-published Skill packages into a content-addressed,
 * read-only AgentScope repository. Every cache hit and download is verified
 * against the immutable binding manifest; package metadata never grants tools.
 */
@Component
public class RuntimeAgentSkillRepositoryFactory {

    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_INSTRUCTION_BYTES = 256 * 1024;
    private static final int MAX_ALWAYS_INSTRUCTION_BYTES = 256 * 1024;
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final Object[] DIGEST_LOCKS = digestLocks(64);

    private final RuntimeAgentSkillCatalogClient controlClient;
    private final ObjectMapper objectMapper;
    private final Path cacheRoot;
    private final long maxPackageBytes;
    private final long maxExpandedBytes;
    private final long maxSingleFileBytes;
    private final int maxFiles;

    public RuntimeAgentSkillRepositoryFactory(
            RuntimeAgentSkillCatalogClient controlClient,
            ObjectMapper objectMapper,
            @Value("${reachai.runtime.skills.cache-directory:${user.home}/.reachai/runtime-skill-cache}")
            String cacheDirectory,
            @Value("${reachai.runtime.skills.max-package-bytes:20971520}") long maxPackageBytes,
            @Value("${reachai.runtime.skills.max-expanded-bytes:104857600}") long maxExpandedBytes,
            @Value("${reachai.runtime.skills.max-single-file-bytes:20971520}") long maxSingleFileBytes,
            @Value("${reachai.runtime.skills.max-files:1024}") int maxFiles) {
        this.controlClient = controlClient;
        this.objectMapper = objectMapper;
        this.cacheRoot = Path.of(cacheDirectory).toAbsolutePath().normalize();
        this.maxPackageBytes = positive(maxPackageBytes, "max-package-bytes");
        this.maxExpandedBytes = positive(maxExpandedBytes, "max-expanded-bytes");
        this.maxSingleFileBytes = positive(maxSingleFileBytes, "max-single-file-bytes");
        if (maxFiles < 1) {
            throw new IllegalArgumentException("reachai.runtime.skills.max-files must be positive");
        }
        this.maxFiles = maxFiles;
    }

    public PreparedSkills prepare(List<RuntimeAgentSkillBindingEntity> bindings) {
        return prepare(bindings, null);
    }

    public PreparedSkills prepare(List<RuntimeAgentSkillBindingEntity> bindings, String agentProjectCode) {
        if (bindings == null || bindings.isEmpty()) {
            return PreparedSkills.empty();
        }
        validateProjectScope(bindings, agentProjectCode);
        List<AgentSkillRepository> repositories = new ArrayList<>();
        List<RuntimeAgentSkillBindingEntity> active = new ArrayList<>();
        List<SkippedSkill> skipped = new ArrayList<>();
        StringBuilder alwaysInstructions = new StringBuilder();
        try {
            List<RuntimeAgentSkillBindingEntity> candidates = bindings.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(binding -> Boolean.TRUE.equals(binding.getEnabled()))
                    .filter(binding -> !"EXPLICIT".equalsIgnoreCase(binding.getActivationMode()))
                    .toList();
            List<RuntimeAgentSkillBindingEntity> executableCandidates = new ArrayList<>();
            for (RuntimeAgentSkillBindingEntity binding : candidates) {
                try {
                    validateBindingIdentity(binding);
                    executableCandidates.add(binding);
                } catch (RuntimeException exception) {
                    if (Boolean.TRUE.equals(binding.getRequired())) {
                        throw unavailable(binding, "BINDING_IDENTITY_INVALID", exception);
                    }
                    skipped.add(skipped(binding, "BINDING_IDENTITY_INVALID"));
                }
            }
            ExecutionStatusBatch executionStatus = resolveExecutionStatus(executableCandidates);
            for (RuntimeAgentSkillBindingEntity binding : executableCandidates) {
                String rejection = executionStatus.rejection(binding);
                if (rejection != null) {
                    if (Boolean.TRUE.equals(binding.getRequired())) {
                        throw unavailable(binding, rejection, null);
                    }
                    skipped.add(skipped(binding, rejection));
                    continue;
                }
                FileSystemSkillRepository repository = null;
                try {
                    Path repositoryRoot = materialize(binding);
                    repository = new FileSystemSkillRepository(
                            repositoryRoot,
                            false,
                            "reachai:" + binding.getPublisher() + "/" + binding.getStandardName()
                                    + "@" + binding.getVersion() + "#" + binding.getSourceSha256(),
                            true);
                    AgentSkill skill = repository.getSkill(binding.getStandardName());
                    if (skill == null) {
                        try {
                            repository.close();
                        } catch (Exception ignored) {
                            // The original load failure remains authoritative.
                        }
                        throw new IllegalStateException("Materialized Agent Skill is not loadable");
                    }
                    if ("ALWAYS".equalsIgnoreCase(binding.getActivationMode())) {
                        appendAlwaysInstruction(alwaysInstructions, binding, skill);
                    }
                    repositories.add(repository);
                    repository = null;
                    active.add(binding);
                } catch (RuntimeException exception) {
                    if (repository != null) {
                        try {
                            repository.close();
                        } catch (Exception ignored) {
                            // Keep the original preparation failure.
                        }
                    }
                    String failureReason = loadFailureReason(exception);
                    if (Boolean.TRUE.equals(binding.getRequired())) {
                        throw unavailable(binding, failureReason, exception);
                    }
                    skipped.add(skipped(binding, failureReason));
                }
            }
            return new PreparedSkills(List.copyOf(repositories), List.copyOf(active),
                    alwaysInstructions.toString(), List.copyOf(skipped));
        } catch (RuntimeException exception) {
            closeRepositories(repositories);
            throw exception;
        }
    }

    private void validateProjectScope(List<RuntimeAgentSkillBindingEntity> bindings, String agentProjectCode) {
        for (RuntimeAgentSkillBindingEntity binding : bindings) {
            if (binding == null || !"PROJECT".equalsIgnoreCase(binding.getVisibility())) continue;
            if (!StringUtils.hasText(binding.getProjectCode())
                    || !StringUtils.hasText(agentProjectCode)
                    || !binding.getProjectCode().trim().equals(agentProjectCode.trim())) {
                throw new IllegalStateException("Project-scoped Agent Skill no longer matches the Agent project: "
                        + binding.getPublisher() + "/" + binding.getStandardName());
            }
        }
    }

    private ExecutionStatusBatch resolveExecutionStatus(List<RuntimeAgentSkillBindingEntity> bindings) {
        if (bindings == null || bindings.isEmpty()) return ExecutionStatusBatch.empty();
        List<ExecutionReference> references = bindings.stream()
                .map(binding -> new ExecutionReference(
                        binding.getSkillId(), binding.getSkillVersionId(), binding.getSourceSha256()))
                .toList();
        List<ExecutionResolution> resolutions;
        try {
            resolutions = controlClient.resolveExecution(references);
        } catch (RuntimeException exception) {
            return new ExecutionStatusBatch(Map.of(), "CONTROL_STATUS_UNAVAILABLE");
        }
        if (resolutions == null || resolutions.size() != references.size()) {
            return new ExecutionStatusBatch(Map.of(), "CONTROL_STATUS_INCOMPLETE");
        }
        Map<Long, ExecutionResolution> byVersion = new LinkedHashMap<>();
        for (ExecutionResolution resolution : resolutions) {
            if (resolution == null || resolution.skillVersionId() == null
                    || byVersion.putIfAbsent(resolution.skillVersionId(), resolution) != null) {
                return new ExecutionStatusBatch(Map.of(), "CONTROL_STATUS_INVALID");
            }
        }
        return new ExecutionStatusBatch(Map.copyOf(byVersion), null);
    }

    private IllegalStateException unavailable(RuntimeAgentSkillBindingEntity binding,
                                              String reason,
                                              RuntimeException cause) {
        String message = "Required Agent Skill is unavailable: " + binding.getPublisher() + "/"
                + binding.getStandardName() + "@" + binding.getVersion() + " (" + reason + ")";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private SkippedSkill skipped(RuntimeAgentSkillBindingEntity binding, String reason) {
        return new SkippedSkill(
                binding.getSkillId(), binding.getSkillVersionId(),
                binding.getPublisher() + "/" + binding.getStandardName(),
                binding.getVersion(), reason);
    }

    private String loadFailureReason(RuntimeException exception) {
        String message = exception == null || exception.getMessage() == null
                ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("manifest")) return "PACKAGE_MANIFEST_INVALID";
        if (message.contains("digest mismatch") || message.contains("sha-256")) {
            return "PACKAGE_DIGEST_MISMATCH";
        }
        if (message.contains("cache")) return "CACHE_VERIFICATION_FAILED";
        return "PACKAGE_LOAD_FAILED";
    }

    Path materialize(RuntimeAgentSkillBindingEntity binding) {
        validateBindingIdentity(binding);
        String digest = binding.getSourceSha256().toLowerCase(Locale.ROOT);
        Path finalDir = cacheRoot.resolve("sha256").resolve(digest.substring(0, 2)).resolve(digest).normalize();
        requireInsideCache(finalDir);
        Object lock = DIGEST_LOCKS[Math.floorMod(digest.hashCode(), DIGEST_LOCKS.length)];
        synchronized (lock) {
            Manifest manifest = manifest(binding);
            if (isVerifiedCache(finalDir, binding, manifest)) {
                return finalDir.resolve("skills");
            }
            if (Files.exists(finalDir, LinkOption.NOFOLLOW_LINKS)) {
                quarantine(finalDir, digest);
            }
            byte[] archive = download(binding);
            if (archive.length > maxPackageBytes) {
                throw new IllegalStateException("Agent Skill package exceeds Runtime package limit");
            }
            String actualArchiveDigest = sha256(archive);
            if (!digest.equals(actualArchiveDigest)) {
                throw new IllegalStateException("Agent Skill package digest mismatch for "
                        + binding.getPublisher() + "/" + binding.getStandardName());
            }

            Path parent = finalDir.getParent();
            Path temporary = parent.resolve(digest + ".partial-" + UUID.randomUUID()).normalize();
            requireInsideCache(temporary);
            try {
                Files.createDirectories(parent);
                extractVerified(archive, temporary, binding, manifest);
                Files.writeString(temporary.resolve(".verified"),
                        digest + "\n" + binding.getContentTreeSha256() + "\n",
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                moveAtomically(temporary, finalDir);
            } catch (Exception exception) {
                safeDelete(temporary);
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Agent Skill package could not be materialized", exception);
            }
            if (!isVerifiedCache(finalDir, binding, manifest)) {
                quarantine(finalDir, digest);
                throw new IllegalStateException("Materialized Agent Skill cache verification failed");
            }
            return finalDir.resolve("skills");
        }
    }

    private byte[] download(RuntimeAgentSkillBindingEntity binding) {
        ResponseEntity<byte[]> response;
        try {
            response = controlClient.getPackage(binding.getSkillId(), binding.getSkillVersionId());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Published Agent Skill package is unavailable: "
                    + binding.getPublisher() + "/" + binding.getStandardName() + "@" + binding.getVersion(),
                    exception);
        }
        byte[] body = response == null ? null : response.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("Published Agent Skill package is empty");
        }
        String headerDigest = response.getHeaders().getFirst("X-ReachAI-Skill-SHA256");
        if (!binding.getSourceSha256().equalsIgnoreCase(headerDigest)) {
            throw new IllegalStateException("Control Agent Skill digest header does not match the binding snapshot");
        }
        return body;
    }

    private Manifest manifest(RuntimeAgentSkillBindingEntity binding) {
        try {
            JsonNode root = objectMapper.readTree(binding.getPackageManifestJson());
            if (root == null || !root.isObject() || !root.path("files").isArray()) {
                throw new IllegalStateException("Agent Skill binding has no valid package manifest");
            }
            requireText(root, "name", binding.getStandardName());
            requireText(root, "sourceRoot", normalizedSourceRoot(binding.getSourceRoot()));
            requireText(root, "sourceSha256", binding.getSourceSha256());
            requireText(root, "contentTreeSha256", binding.getContentTreeSha256());
            Map<String, ManifestFile> files = new LinkedHashMap<>();
            Map<String, String> portablePaths = new LinkedHashMap<>();
            long expanded = 0L;
            for (JsonNode value : root.path("files")) {
                String path = safeRelativePath(value.path("path").asText(null));
                long size = value.path("size").asLong(-1L);
                String sha = value.path("sha256").asText("").toLowerCase(Locale.ROOT);
                if (size < 0L || size > maxSingleFileBytes || !sha.matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("Agent Skill manifest contains an invalid file entry: " + path);
                }
                if ("SKILL.md".equals(path) && size > MAX_INSTRUCTION_BYTES) {
                    throw new IllegalStateException(
                            "Agent Skill SKILL.md exceeds the Runtime instruction limit");
                }
                if (files.putIfAbsent(path, new ManifestFile(path, size, sha)) != null) {
                    throw new IllegalStateException("Agent Skill manifest contains a duplicate path: " + path);
                }
                String collided = portablePaths.putIfAbsent(portablePathKey(path), path);
                if (collided != null && !collided.equals(path)) {
                    throw new IllegalStateException(
                            "Agent Skill manifest contains paths that collide on a portable filesystem");
                }
                expanded = Math.addExact(expanded, size);
                if (expanded > maxExpandedBytes || files.size() > maxFiles) {
                    throw new IllegalStateException("Agent Skill manifest exceeds Runtime extraction limits");
                }
            }
            if (!files.containsKey("SKILL.md")) {
                throw new IllegalStateException("Agent Skill manifest is missing SKILL.md");
            }
            rejectFileAncestorCollisions(files.keySet(), portablePaths);
            if (!binding.getContentTreeSha256().equalsIgnoreCase(treeSha256(files.values()))) {
                throw new IllegalStateException("Agent Skill manifest content tree digest mismatch");
            }
            return new Manifest(Map.copyOf(files), expanded);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Agent Skill binding package manifest is invalid", exception);
        }
    }

    private void extractVerified(byte[] archive,
                                 Path temporary,
                                 RuntimeAgentSkillBindingEntity binding,
                                 Manifest manifest) throws IOException {
        Path skillDir = temporary.resolve("skills").resolve(binding.getStandardName()).normalize();
        requireInside(temporary, skillDir);
        Files.createDirectories(skillDir);
        Set<String> seen = new HashSet<>();
        long expanded = 0L;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String archivePath = normalizeArchivePath(entry.getName());
                if (archivePath == null || ignoredMetadata(archivePath) || entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String sourceRoot = normalizedSourceRoot(binding.getSourceRoot());
                if (!sourceRoot.isEmpty() && !archivePath.startsWith(sourceRoot)) {
                    throw new IllegalStateException("Unexpected file outside Agent Skill root: " + archivePath);
                }
                String relative = sourceRoot.isEmpty() ? archivePath : archivePath.substring(sourceRoot.length());
                relative = safeRelativePath(relative);
                ManifestFile expected = manifest.files().get(relative);
                if (expected == null) {
                    throw new IllegalStateException("Agent Skill ZIP contains an unlisted file: " + relative);
                }
                if (!seen.add(relative)) {
                    throw new IllegalStateException("Agent Skill ZIP contains a duplicate file: " + relative);
                }
                Path target = skillDir.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
                requireInside(skillDir, target);
                Files.createDirectories(target.getParent());
                MessageDigest digest = digest();
                long written = 0L;
                try (OutputStream output = new java.security.DigestOutputStream(
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), digest)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        written += read;
                        expanded += read;
                        if (written > maxSingleFileBytes || written > expected.size()
                                || expanded > maxExpandedBytes) {
                            throw new IllegalStateException("Agent Skill ZIP exceeds declared extraction limits");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                if (written != expected.size() || !expected.sha256().equals(hex(digest.digest()))) {
                    throw new IllegalStateException("Agent Skill file digest mismatch: " + relative);
                }
                zip.closeEntry();
            }
        }
        if (!seen.equals(manifest.files().keySet()) || expanded != manifest.expandedBytes()) {
            throw new IllegalStateException("Agent Skill ZIP does not match its published file manifest");
        }
    }

    private boolean isVerifiedCache(Path finalDir,
                                    RuntimeAgentSkillBindingEntity binding,
                                    Manifest manifest) {
        try {
            Path marker = finalDir.resolve(".verified");
            Path skillDir = finalDir.resolve("skills").resolve(binding.getStandardName()).normalize();
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            String markerText = Files.readString(marker);
            if (!markerText.equals(binding.getSourceSha256() + "\n" + binding.getContentTreeSha256() + "\n")) {
                return false;
            }
            Map<String, Path> actual = new HashMap<>();
            try (var paths = Files.walk(skillDir)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .forEach(path -> actual.put(skillDir.relativize(path).toString().replace('\\', '/'), path));
            }
            if (!actual.keySet().equals(manifest.files().keySet())) {
                return false;
            }
            for (ManifestFile expected : manifest.files().values()) {
                Path file = actual.get(expected.path());
                if (file == null || Files.size(file) != expected.size()
                        || !expected.sha256().equals(sha256(file))) {
                    return false;
                }
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private void quarantine(Path directory, String digest) {
        try {
            Path quarantineRoot = cacheRoot.resolve("quarantine").normalize();
            requireInsideCache(quarantineRoot);
            Files.createDirectories(quarantineRoot);
            Path target = quarantineRoot.resolve(digest + "-" + Instant.now().toEpochMilli()
                    + "-" + UUID.randomUUID()).normalize();
            requireInsideCache(target);
            Files.move(directory, target);
        } catch (IOException exception) {
            throw new IllegalStateException("Corrupted Agent Skill cache could not be quarantined", exception);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            try {
                Files.move(source, target);
            } catch (IOException concurrentOrStorageFailure) {
                acceptConcurrentMaterializationOrThrow(source, target, concurrentOrStorageFailure);
            }
        } catch (IOException concurrentOrStorageFailure) {
            // Across Runtime processes, Windows can surface a same-target rename race as
            // AccessDeniedException. The caller performs a full manifest verification next.
            acceptConcurrentMaterializationOrThrow(source, target, concurrentOrStorageFailure);
        }
    }

    private void acceptConcurrentMaterializationOrThrow(Path source,
                                                        Path target,
                                                        IOException moveFailure) throws IOException {
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw moveFailure;
        }
        safeDelete(source);
    }

    private void safeDelete(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        requireInsideCache(directory);
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A failed cleanup must not hide the original verification error.
                }
            });
        } catch (IOException ignored) {
            // A failed cleanup must not hide the original verification error.
        }
    }

    private void validateBindingIdentity(RuntimeAgentSkillBindingEntity binding) {
        if (binding == null || binding.getSkillId() == null || binding.getSkillVersionId() == null
                || !StringUtils.hasText(binding.getPublisher())
                || !StringUtils.hasText(binding.getStandardName())
                || !StringUtils.hasText(binding.getVersion())
                || binding.getSourceSha256() == null
                || !binding.getSourceSha256().matches("[0-9a-fA-F]{64}")
                || binding.getContentTreeSha256() == null
                || !binding.getContentTreeSha256().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Agent Skill binding identity is incomplete");
        }
    }

    private String normalizedSourceRoot(String value) {
        if (!StringUtils.hasText(value)) return "";
        String root = value.trim().replace('\\', '/');
        return root.endsWith("/") ? root : root + "/";
    }

    private String normalizeArchivePath(String value) {
        if (!StringUtils.hasText(value)) return null;
        String path = value.replace('\\', '/');
        if (path.startsWith("/") || path.contains(":") || path.contains("\u0000")) {
            throw new IllegalStateException("Agent Skill ZIP contains an unsafe path");
        }
        return path;
    }

    private String safeRelativePath(String value) {
        String path = normalizeArchivePath(value);
        if (!StringUtils.hasText(path) || path.endsWith("/") || path.startsWith("./")
                || path.contains("//")) {
            throw new IllegalStateException("Agent Skill manifest contains an unsafe relative path");
        }
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().equals(".")) {
            throw new IllegalStateException("Agent Skill manifest contains path traversal");
        }
        String result = normalized.toString().replace('\\', '/');
        if (!result.equals(path)) {
            throw new IllegalStateException("Agent Skill manifest path is not normalized: " + path);
        }
        if (result.length() > 1024) {
            throw new IllegalStateException("Agent Skill manifest path exceeds the portable limit");
        }
        for (String segment : result.split("/")) {
            validatePortableSegment(segment);
        }
        return result;
    }

    private void validatePortableSegment(String segment) {
        if (segment.endsWith(".") || segment.endsWith(" ")
                || segment.chars().anyMatch(value -> value < 32 || value == 127
                        || value == '<' || value == '>' || value == ':' || value == '"'
                        || value == '|' || value == '?' || value == '*')
                || segment.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
            throw new IllegalStateException("Agent Skill manifest contains a non-portable path");
        }
        String basename = segment.contains(".") ? segment.substring(0, segment.indexOf('.')) : segment;
        if (WINDOWS_RESERVED_NAMES.contains(basename.toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("Agent Skill manifest contains a reserved filesystem path");
        }
    }

    private String portablePathKey(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private void rejectFileAncestorCollisions(Set<String> paths, Map<String, String> portablePaths) {
        for (String path : paths) {
            int slash = path.indexOf('/');
            while (slash > 0) {
                if (portablePaths.containsKey(portablePathKey(path.substring(0, slash)))) {
                    throw new IllegalStateException(
                            "Agent Skill manifest contains a file/directory path conflict");
                }
                slash = path.indexOf('/', slash + 1);
            }
        }
    }

    private boolean ignoredMetadata(String path) {
        return path.equals(".DS_Store") || path.endsWith("/.DS_Store")
                || path.equals("__MACOSX") || path.startsWith("__MACOSX/");
    }

    private void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText(null))) {
            throw new IllegalStateException("Agent Skill manifest " + field + " does not match binding snapshot");
        }
    }

    private void requireInsideCache(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(cacheRoot) || !normalized.startsWith(cacheRoot)) {
            throw new IllegalStateException("Agent Skill cache path escaped its configured root");
        }
    }

    private void requireInside(Path parent, Path child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (normalizedChild.equals(normalizedParent) || !normalizedChild.startsWith(normalizedParent)) {
            throw new IllegalStateException("Agent Skill extraction path escaped its target directory");
        }
    }

    private String sha256(byte[] value) {
        return hex(digest().digest(value));
    }

    private String treeSha256(java.util.Collection<ManifestFile> files) {
        MessageDigest digest = digest();
        files.stream().sorted(Comparator.comparing(ManifestFile::path)).forEach(file -> {
            digest.update(file.path().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(Long.toString(file.size()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        });
        return hex(digest.digest());
    }

    private String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return hex(digest.digest());
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private long positive(long value, String name) {
        if (value < 1L) throw new IllegalArgumentException("reachai.runtime.skills." + name + " must be positive");
        return value;
    }

    private static Object[] digestLocks(int count) {
        Object[] locks = new Object[count];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private void appendAlwaysInstruction(StringBuilder target,
                                         RuntimeAgentSkillBindingEntity binding,
                                         AgentSkill skill) {
        StringBuilder block = new StringBuilder();
        block.append("<reachai-always-skill name=\"")
                .append(binding.getStandardName())
                .append("\" publisher=\"")
                .append(binding.getPublisher())
                .append("\" version=\"")
                .append(binding.getVersion())
                .append("\" sha256=\"")
                .append(binding.getSourceSha256())
                .append("\">\n")
                .append(escapeAlwaysSkillBoundary(skill.getSkillContent()))
                .append("\n</reachai-always-skill>");
        String separator = target.length() > 0 ? "\n\n" : "";
        int combinedBytes = target.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + separator.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + block.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (combinedBytes > MAX_ALWAYS_INSTRUCTION_BYTES) {
            throw new IllegalStateException("Combined ALWAYS Agent Skill instructions exceed the context limit");
        }
        target.append(separator);
        target.append(block);
    }

    private String escapeAlwaysSkillBoundary(String value) {
        if (value == null) return "";
        return value
                .replace("<reachai-always-skill", "&lt;reachai-always-skill")
                .replace("</reachai-always-skill>", "&lt;/reachai-always-skill&gt;");
    }

    private static void closeRepositories(List<AgentSkillRepository> repositories) {
        for (AgentSkillRepository repository : repositories) {
            try {
                repository.close();
            } catch (Exception ignored) {
                // Repository cleanup is best effort; content cache remains immutable.
            }
        }
    }

    private record ManifestFile(String path, long size, String sha256) {
    }

    private record Manifest(Map<String, ManifestFile> files, long expandedBytes) {
    }

    private record ExecutionStatusBatch(
            Map<Long, ExecutionResolution> resolutions,
            String batchFailureReason) {

        static ExecutionStatusBatch empty() {
            return new ExecutionStatusBatch(Map.of(), null);
        }

        String rejection(RuntimeAgentSkillBindingEntity binding) {
            if (batchFailureReason != null) return batchFailureReason;
            ExecutionResolution resolution = resolutions.get(binding.getSkillVersionId());
            if (resolution == null) return "CONTROL_STATUS_MISSING";
            if (!binding.getSkillId().equals(resolution.skillId())) return "SKILL_ID_MISMATCH";
            if (!resolution.executable()) {
                return StringUtils.hasText(resolution.reason())
                        ? resolution.reason().trim().toUpperCase(Locale.ROOT)
                        : "STATUS_" + resolution.status();
            }
            if (!binding.getSourceSha256().equalsIgnoreCase(resolution.sourceSha256())) {
                return "DIGEST_MISMATCH";
            }
            return null;
        }
    }

    public record SkippedSkill(
            Long skillId,
            Long skillVersionId,
            String identity,
            String version,
            String reason) {
    }

    public record PreparedSkills(
            List<AgentSkillRepository> repositories,
            List<RuntimeAgentSkillBindingEntity> activeBindings,
            String alwaysInstructions,
            List<SkippedSkill> skippedSkills) implements AutoCloseable {

        public PreparedSkills(
                List<AgentSkillRepository> repositories,
                List<RuntimeAgentSkillBindingEntity> activeBindings,
                String alwaysInstructions) {
            this(repositories, activeBindings, alwaysInstructions, List.of());
        }

        static PreparedSkills empty() {
            return new PreparedSkills(List.of(), List.of(), "", List.of());
        }

        public boolean enabled() {
            return repositories != null && !repositories.isEmpty();
        }

        @Override
        public void close() {
            closeRepositories(repositories == null ? List.of() : repositories);
        }
    }
}
