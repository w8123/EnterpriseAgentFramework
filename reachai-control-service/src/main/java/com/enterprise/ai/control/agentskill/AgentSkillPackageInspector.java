package com.enterprise.ai.control.agentskill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Validates one portable Agent Skills package without extracting it to the host filesystem.
 * The original archive remains immutable; this class only builds a normalized, digest-addressed view.
 */
@Component
public class AgentSkillPackageInspector {

    static final Pattern STANDARD_NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*$");
    private static final Pattern WINDOWS_FORBIDDEN = Pattern.compile("[<>:\"|?*]");
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final int MAX_INSTRUCTION_BYTES = 256 * 1024;
    private static final int MAX_PREVIEW_CHARS = 100_000;
    private static final int MAX_HOST_METADATA_BYTES = 256 * 1024;
    private static final int MAX_HOST_TOOL_DEPENDENCIES = 100;
    private static final int MAX_HOST_METADATA_VALUE_CHARS = 2_048;
    private static final int MAX_BUNDLE_CANDIDATES = 256;
    private static final String CODEX_HOST_METADATA_PATH = "agents/openai.yaml";
    private static final Set<String> CODEX_TOOL_DEPENDENCY_FIELDS = Set.of(
            "type", "value", "description", "transport", "url");
    private static final Set<String> EXECUTABLE_SUFFIXES = Set.of(
            ".sh", ".bash", ".zsh", ".fish", ".ps1", ".bat", ".cmd",
            ".py", ".rb", ".pl", ".js", ".mjs", ".cjs", ".exe", ".dll", ".jar");
    private static final Set<String> STANDARD_FRONTMATTER_FIELDS = Set.of(
            "name", "description", "license", "compatibility", "metadata", "allowed-tools");

    private final long maxArchiveBytes;
    private final long maxExpandedBytes;
    private final long maxSingleFileBytes;
    private final int maxFiles;
    private final long maxBundleArchiveBytes;
    private final long maxBundleExpandedBytes;
    private final long maxBundleSingleFileBytes;
    private final int maxBundleFiles;

    @Autowired
    public AgentSkillPackageInspector(
            @Value("${reachai.skill.max-package-bytes:20971520}") long maxArchiveBytes,
            @Value("${reachai.skill.max-expanded-bytes:104857600}") long maxExpandedBytes,
            @Value("${reachai.skill.max-single-file-bytes:20971520}") long maxSingleFileBytes,
            @Value("${reachai.skill.max-files:1024}") int maxFiles,
            @Value("${reachai.skill.max-bundle-bytes:67108864}") long maxBundleArchiveBytes,
            @Value("${reachai.skill.max-bundle-expanded-bytes:268435456}") long maxBundleExpandedBytes,
            @Value("${reachai.skill.max-bundle-single-file-bytes:67108864}") long maxBundleSingleFileBytes,
            @Value("${reachai.skill.max-bundle-files:8192}") int maxBundleFiles) {
        this.maxArchiveBytes = positive(maxArchiveBytes, "max-package-bytes");
        this.maxExpandedBytes = positive(maxExpandedBytes, "max-expanded-bytes");
        this.maxSingleFileBytes = positive(maxSingleFileBytes, "max-single-file-bytes");
        this.maxFiles = Math.toIntExact(positive(maxFiles, "max-files"));
        this.maxBundleArchiveBytes = positive(maxBundleArchiveBytes, "max-bundle-bytes");
        this.maxBundleExpandedBytes = positive(maxBundleExpandedBytes, "max-bundle-expanded-bytes");
        this.maxBundleSingleFileBytes = positive(maxBundleSingleFileBytes, "max-bundle-single-file-bytes");
        this.maxBundleFiles = Math.toIntExact(positive(maxBundleFiles, "max-bundle-files"));
        if (this.maxBundleArchiveBytes < this.maxArchiveBytes
                || this.maxBundleExpandedBytes < this.maxExpandedBytes
                || this.maxBundleSingleFileBytes < this.maxSingleFileBytes
                || this.maxBundleFiles < this.maxFiles) {
            throw new IllegalArgumentException("Agent Skill bundle limits must cover single-package limits");
        }
    }

    /** Test-friendly constructor preserving the historical single-package limit semantics. */
    public AgentSkillPackageInspector(long maxArchiveBytes,
                                      long maxExpandedBytes,
                                      long maxSingleFileBytes,
                                      int maxFiles) {
        this(maxArchiveBytes, maxExpandedBytes, maxSingleFileBytes, maxFiles,
                maxArchiveBytes, maxExpandedBytes, maxSingleFileBytes, maxFiles);
    }

    public PackageLimits limits() {
        return new PackageLimits(
                maxArchiveBytes,
                maxExpandedBytes,
                maxSingleFileBytes,
                maxFiles,
                MAX_INSTRUCTION_BYTES);
    }

    public BundleLimits bundleLimits() {
        return new BundleLimits(
                maxBundleArchiveBytes,
                maxBundleExpandedBytes,
                maxBundleSingleFileBytes,
                maxBundleFiles);
    }

    public PackageInspection inspect(byte[] archive) {
        Map<String, byte[]> archiveFiles = readArchive(archive);
        List<String> descriptors = skillDescriptors(archiveFiles);
        if (descriptors.size() != 1) {
            throw AgentSkillException.invalidPackage(
                    "A package must contain exactly one SKILL.md; found " + descriptors.size()
                            + ". Use bundle discovery to select one Skill from a repository or plugin ZIP");
        }

        String descriptorPath = descriptors.get(0);
        String skillRoot = descriptorPath.substring(0, descriptorPath.length() - "SKILL.md".length());
        if (skillRoot.length() > 512) {
            throw AgentSkillException.invalidPackage("Skill package root exceeds the portable path limit");
        }
        Map<String, byte[]> files = normalizeSkillTree(archiveFiles, skillRoot);
        if (files.get("SKILL.md").length > MAX_INSTRUCTION_BYTES) {
            throw AgentSkillException.invalidPackage(
                    "SKILL.md exceeds the 256 KiB progressive-loading instruction limit");
        }
        String skillMarkdown = decodeUtf8(files.get("SKILL.md"), "SKILL.md");
        Map<String, Object> frontmatter = parseFrontmatter(skillMarkdown);
        validateOptionalFrontmatter(frontmatter);
        String name = requiredText(frontmatter.get("name"), "name");
        String description = requiredText(frontmatter.get("description"), "description");
        validateName(name, skillRoot);
        if (description.length() > 1024) {
            throw AgentSkillException.invalidPackage("SKILL.md description must not exceed 1024 characters");
        }

        List<FileEntry> manifestFiles = files.entrySet().stream()
                .map(entry -> fileEntry(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(FileEntry::path))
                .toList();
        List<String> scriptFiles = manifestFiles.stream()
                .filter(file -> file.kind() == FileKind.SCRIPT)
                .map(FileEntry::path)
                .toList();
        CodexHostMetadataInspection codexHostMetadata = inspectCodexHostMetadata(files);
        List<String> warnings = new ArrayList<>();
        if (!scriptFiles.isEmpty()) {
            warnings.add("Package contains executable or script files; runtime execution requires separate policy approval");
        }
        if (frontmatter.containsKey("allowed-tools")) {
            warnings.add("allowed-tools is preserved as package metadata and does not grant ReachAI Tool permissions");
        }
        if (codexHostMetadata.present() && !codexHostMetadata.valid()) {
            warnings.add("agents/openai.yaml is preserved but is not valid Codex host metadata ("
                    + codexHostMetadata.errorCode() + ")");
        }
        if (codexHostMetadata.toolDependencyCount() > 0) {
            warnings.add("agents/openai.yaml declares " + codexHostMetadata.toolDependencyCount()
                    + " host tool dependencies; ReachAI preserves them but does not grant Tool permissions"
                    + " or resolve MCP dependencies");
        }
        if (codexHostMetadata.present() && Boolean.FALSE.equals(codexHostMetadata.allowImplicitInvocation())) {
            warnings.add("agents/openai.yaml disables implicit Codex invocation; ReachAI preserves this host policy"
                    + " while Agent activation remains an explicit binding decision");
        }
        List<String> extensionFields = frontmatter.keySet().stream()
                .filter(field -> !STANDARD_FRONTMATTER_FIELDS.contains(field))
                .sorted()
                .toList();

        return new PackageInspection(
                name,
                description,
                optionalText(frontmatter.get("license")),
                optionalText(frontmatter.get("compatibility")),
                declaredVersion(frontmatter),
                sha256(archive),
                treeSha256(manifestFiles),
                skillRoot,
                Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter)),
                manifestFiles,
                scriptFiles,
                extensionFields,
                codexHostMetadata,
                List.copyOf(warnings));
    }

    /**
     * Safely discovers all Agent Skills contained in a repository or plugin ZIP.
     * Discovery never extracts files, executes scripts, or persists the uploaded bundle.
     */
    public BundleDiscovery discoverBundle(byte[] archive) {
        Map<String, byte[]> archiveFiles = readBundleArchive(archive);
        List<String> descriptors = skillDescriptors(archiveFiles);
        validateBundleDescriptorCount(descriptors);
        String bundleSourceSha256 = sha256(archive);
        List<BundleCandidate> candidates = new ArrayList<>();
        for (String descriptor : descriptors) {
            String sourceRoot = candidateRoot(descriptor);
            if (containsNestedSkill(descriptors, sourceRoot)) {
                candidates.add(BundleCandidate.invalid(
                        sourceRoot,
                        "NESTED_SKILL_DESCRIPTOR",
                        "The candidate contains another SKILL.md; select a leaf Skill directory"));
                continue;
            }
            try {
                SelectedPackage selected = selectCandidate(
                        archiveFiles, bundleSourceSha256, sourceRoot);
                candidates.add(BundleCandidate.valid(sourceRoot, selected.inspection()));
            } catch (AgentSkillException invalidCandidate) {
                candidates.add(BundleCandidate.invalid(
                        sourceRoot, invalidCandidate.code(), invalidCandidate.getMessage()));
            }
        }
        candidates.sort(Comparator.comparing(BundleCandidate::sourceRoot));
        return new BundleDiscovery(
                "reachai.agent-skill-bundle-discovery.v1",
                bundleSourceSha256,
                archiveFiles.size(),
                candidates.size(),
                descriptors.size() > 1,
                List.copyOf(candidates));
    }

    /**
     * Selects one discovered Skill subtree and rewrites it as a deterministic root-layout package.
     * Repository siblings and files outside that Skill directory are intentionally excluded.
     */
    public SelectedPackage selectFromBundle(byte[] archive, String sourceRoot) {
        Map<String, byte[]> archiveFiles = readBundleArchive(archive);
        List<String> descriptors = skillDescriptors(archiveFiles);
        validateBundleDescriptorCount(descriptors);
        String normalizedRoot = normalizeCandidateRoot(sourceRoot);
        String expectedDescriptor = normalizedRoot.isEmpty()
                ? "SKILL.md"
                : normalizedRoot + "/SKILL.md";
        if (!descriptors.contains(expectedDescriptor)) {
            throw AgentSkillException.invalidPackage(
                    "Selected Skill root does not exist in the uploaded bundle: "
                            + displayCandidateRoot(normalizedRoot));
        }
        if (containsNestedSkill(descriptors, normalizedRoot)) {
            throw AgentSkillException.invalidPackage(
                    "Selected Skill root contains another SKILL.md; select a leaf Skill directory");
        }
        return selectCandidate(archiveFiles, sha256(archive), normalizedRoot);
    }

    private SelectedPackage selectCandidate(Map<String, byte[]> archiveFiles,
                                            String bundleSourceSha256,
                                            String sourceRoot) {
        Map<String, byte[]> selectedFiles = selectSkillTree(archiveFiles, sourceRoot);
        byte[] selectedArchive = canonicalZip(selectedFiles);
        PackageInspection inspection = inspect(selectedArchive);
        validateCandidateDirectoryName(sourceRoot, inspection.name());
        return new SelectedPackage(sourceRoot, bundleSourceSha256, selectedArchive, inspection);
    }

    private Map<String, byte[]> selectSkillTree(Map<String, byte[]> archiveFiles,
                                                String sourceRoot) {
        String prefix = sourceRoot.isEmpty() ? "" : sourceRoot + "/";
        Map<String, byte[]> selected = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : archiveFiles.entrySet()) {
            String path = entry.getKey();
            if (isPackagingNoise(path) || (!sourceRoot.isEmpty() && !path.startsWith(prefix))) {
                continue;
            }
            String relative = sourceRoot.isEmpty() ? path : path.substring(prefix.length());
            if (!StringUtils.hasText(relative)) {
                continue;
            }
            if (selected.putIfAbsent(relative, entry.getValue()) != null) {
                throw AgentSkillException.invalidPackage(
                        "Duplicate normalized Skill path: " + relative);
            }
        }
        if (!selected.containsKey("SKILL.md")) {
            throw AgentSkillException.invalidPackage(
                    "SKILL.md is missing from the selected Skill root");
        }
        return selected;
    }

    private byte[] canonicalZip(Map<String, byte[]> files) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> file : files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new AgentSkillException(
                    "SKILL_PACKAGE_CANONICALIZATION_FAILED",
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Selected Skill package could not be canonicalized",
                    exception);
        }
        byte[] archive = output.toByteArray();
        if (archive.length > maxArchiveBytes) {
            throw AgentSkillException.invalidPackage(
                    "Selected Skill ZIP exceeds the configured upload limit");
        }
        return archive;
    }

    private void validateCandidateDirectoryName(String sourceRoot, String name) {
        if (sourceRoot.isEmpty()) {
            return;
        }
        String directoryName = sourceRoot.substring(sourceRoot.lastIndexOf('/') + 1);
        if (!name.equals(directoryName)) {
            throw AgentSkillException.invalidPackage(
                    "SKILL.md name must match its package directory: expected " + directoryName);
        }
    }

    private void validateBundleDescriptorCount(List<String> descriptors) {
        if (descriptors.isEmpty()) {
            throw AgentSkillException.invalidPackage(
                    "The uploaded ZIP does not contain a SKILL.md");
        }
        if (descriptors.size() > MAX_BUNDLE_CANDIDATES) {
            throw AgentSkillException.invalidPackage(
                    "The uploaded ZIP contains more than " + MAX_BUNDLE_CANDIDATES
                            + " Skill candidates");
        }
    }

    private List<String> skillDescriptors(Map<String, byte[]> archiveFiles) {
        return archiveFiles.keySet().stream()
                .filter(path -> "SKILL.md".equals(path) || path.endsWith("/SKILL.md"))
                .sorted()
                .toList();
    }

    private String candidateRoot(String descriptorPath) {
        return "SKILL.md".equals(descriptorPath)
                ? ""
                : descriptorPath.substring(0, descriptorPath.length() - "/SKILL.md".length());
    }

    private String normalizeCandidateRoot(String sourceRoot) {
        if (!StringUtils.hasText(sourceRoot)) {
            return "";
        }
        String normalized = normalizeArchivePath(sourceRoot.trim().replace('\\', '/'));
        if (normalized.endsWith("/SKILL.md") || "SKILL.md".equals(normalized)) {
            throw AgentSkillException.invalidPackage(
                    "Select the Skill directory, not the SKILL.md file");
        }
        return normalized;
    }

    private boolean containsNestedSkill(List<String> descriptors, String sourceRoot) {
        String ownDescriptor = sourceRoot.isEmpty()
                ? "SKILL.md"
                : sourceRoot + "/SKILL.md";
        String prefix = sourceRoot.isEmpty() ? "" : sourceRoot + "/";
        return descriptors.stream().anyMatch(descriptor ->
                !descriptor.equals(ownDescriptor)
                        && (sourceRoot.isEmpty() || descriptor.startsWith(prefix)));
    }

    private String displayCandidateRoot(String sourceRoot) {
        return sourceRoot.isEmpty() ? "<archive-root>" : sourceRoot;
    }

    /** Reads one reviewed package file without extracting or executing it on the host. */
    public PackageFilePreview previewFile(byte[] archive, String requestedPath) {
        if (!StringUtils.hasText(requestedPath)) {
            throw AgentSkillException.invalidPackage("Skill preview path is required");
        }
        Map<String, byte[]> archiveFiles = readArchive(archive);
        List<String> descriptors = skillDescriptors(archiveFiles);
        if (descriptors.size() != 1) {
            throw AgentSkillException.invalidPackage("A package must contain exactly one SKILL.md");
        }
        String descriptorPath = descriptors.get(0);
        String skillRoot = descriptorPath.substring(0, descriptorPath.length() - "SKILL.md".length());
        Map<String, byte[]> files = normalizeSkillTree(archiveFiles, skillRoot);
        String normalizedPath = normalizeArchivePath(requestedPath.trim().replace('\\', '/'));
        byte[] content = files.get(normalizedPath);
        if (content == null) {
            throw AgentSkillException.notFound("Skill package file not found: " + normalizedPath);
        }
        FileEntry entry = fileEntry(normalizedPath, content);
        String text = previewText(content);
        if (text == null) {
            return new PackageFilePreview(entry.path(), entry.size(), entry.sha256(), entry.kind(),
                    false, false, null);
        }
        boolean truncated = text.length() > MAX_PREVIEW_CHARS;
        return new PackageFilePreview(entry.path(), entry.size(), entry.sha256(), entry.kind(),
                true, truncated, truncated ? text.substring(0, MAX_PREVIEW_CHARS) : text);
    }

    private Map<String, byte[]> readArchive(byte[] archive) {
        if (archive == null || archive.length == 0) {
            throw AgentSkillException.invalidPackage("Skill ZIP is required");
        }
        if (archive.length > maxArchiveBytes) {
            throw AgentSkillException.invalidPackage(
                    "Skill ZIP exceeds the configured upload limit");
        }
        return readZip(archive, maxExpandedBytes, maxSingleFileBytes, maxFiles);
    }

    private Map<String, byte[]> readBundleArchive(byte[] archive) {
        if (archive == null || archive.length == 0) {
            throw AgentSkillException.invalidPackage("Skill repository ZIP is required");
        }
        if (archive.length > maxBundleArchiveBytes) {
            throw AgentSkillException.invalidPackage(
                    "Skill repository ZIP exceeds the configured bundle limit");
        }
        return readZip(archive, maxBundleExpandedBytes, maxBundleSingleFileBytes, maxBundleFiles);
    }

    private Map<String, byte[]> readZip(byte[] archive,
                                        long expandedLimit,
                                        long singleFileLimit,
                                        int fileLimit) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, String> portablePaths = new LinkedHashMap<>();
        long expandedBytes = 0L;
        int fileCount = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                String path = normalizeArchivePath(entry.getName());
                if (entry.isDirectory()) {
                    input.closeEntry();
                    continue;
                }
                if (++fileCount > fileLimit) {
                    throw AgentSkillException.invalidPackage("Skill ZIP contains too many files");
                }
                if (files.containsKey(path)) {
                    throw AgentSkillException.invalidPackage("Skill ZIP contains duplicate path: " + path);
                }
                String portableKey = portablePathKey(path);
                String collided = portablePaths.putIfAbsent(portableKey, path);
                if (collided != null && !collided.equals(path)) {
                    throw AgentSkillException.invalidPackage(
                            "Skill ZIP contains paths that collide on a portable filesystem: "
                                    + collided + " and " + path);
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                long fileBytes = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    fileBytes += read;
                    expandedBytes += read;
                    if (fileBytes > singleFileLimit) {
                        throw AgentSkillException.invalidPackage("Skill file exceeds the configured limit: " + path);
                    }
                    if (expandedBytes > expandedLimit) {
                        throw AgentSkillException.invalidPackage("Skill ZIP expands beyond the configured limit");
                    }
                    content.write(buffer, 0, read);
                }
                files.put(path, content.toByteArray());
                input.closeEntry();
            }
        } catch (AgentSkillException expected) {
            throw expected;
        } catch (ZipException invalidZip) {
            throw AgentSkillException.invalidPackage("Skill package is not a valid ZIP archive");
        } catch (IOException exception) {
            throw new AgentSkillException("SKILL_PACKAGE_READ_FAILED",
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Skill ZIP could not be read", exception);
        }
        if (files.isEmpty()) {
            throw AgentSkillException.invalidPackage("Skill ZIP contains no files");
        }
        rejectFileAncestorCollisions(files.keySet(), portablePaths);
        return files;
    }

    private void rejectFileAncestorCollisions(Set<String> paths, Map<String, String> portablePaths) {
        for (String path : paths) {
            int slash = path.indexOf('/');
            while (slash > 0) {
                String ancestor = path.substring(0, slash);
                String ancestorFile = portablePaths.get(portablePathKey(ancestor));
                if (ancestorFile != null) {
                    throw AgentSkillException.invalidPackage(
                            "Skill ZIP contains a file/directory path conflict: "
                                    + ancestorFile + " and " + path);
                }
                slash = path.indexOf('/', slash + 1);
            }
        }
    }

    private Map<String, byte[]> normalizeSkillTree(Map<String, byte[]> archiveFiles, String skillRoot) {
        Map<String, byte[]> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : archiveFiles.entrySet()) {
            String path = entry.getKey();
            if (isPackagingNoise(path)) {
                continue;
            }
            if (!skillRoot.isEmpty() && !path.startsWith(skillRoot)) {
                throw AgentSkillException.invalidPackage("Unexpected file outside the Skill root: " + path);
            }
            String relative = skillRoot.isEmpty() ? path : path.substring(skillRoot.length());
            if (!StringUtils.hasText(relative)) {
                continue;
            }
            if (normalized.putIfAbsent(relative, entry.getValue()) != null) {
                throw AgentSkillException.invalidPackage("Duplicate normalized Skill path: " + relative);
            }
        }
        if (!normalized.containsKey("SKILL.md")) {
            throw AgentSkillException.invalidPackage("SKILL.md is missing from the normalized package root");
        }
        return normalized;
    }

    private Map<String, Object> parseFrontmatter(String markdown) {
        String content = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        String[] lines = content.split("\\r?\\n", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            throw AgentSkillException.invalidPackage("SKILL.md must start with YAML frontmatter");
        }
        int end = -1;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if ("---".equals(line) || "...".equals(line)) {
                end = index;
                break;
            }
        }
        if (end < 0) {
            throw AgentSkillException.invalidPackage("SKILL.md YAML frontmatter is not closed");
        }
        String yamlText = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, end));
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(256 * 1024);
        try {
            Object parsed = new Yaml(new SafeConstructor(options)).load(yamlText);
            if (!(parsed instanceof Map<?, ?> values)) {
                throw AgentSkillException.invalidPackage("SKILL.md frontmatter must be a YAML object");
            }
            Map<String, Object> frontmatter = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !StringUtils.hasText(key)) {
                    throw AgentSkillException.invalidPackage("SKILL.md frontmatter keys must be non-empty strings");
                }
                frontmatter.put(key, entry.getValue());
            }
            return frontmatter;
        } catch (AgentSkillException expected) {
            throw expected;
        } catch (YAMLException invalidYaml) {
            throw AgentSkillException.invalidPackage("SKILL.md frontmatter is invalid YAML: " + invalidYaml.getMessage());
        }
    }

    private void validateName(String name, String skillRoot) {
        if (name.length() > 64 || !STANDARD_NAME.matcher(name).matches()) {
            throw AgentSkillException.invalidPackage(
                    "SKILL.md name must use lowercase letters, numbers, and single hyphens only");
        }
        if (!skillRoot.isEmpty()) {
            String withoutSlash = skillRoot.substring(0, skillRoot.length() - 1);
            String rootName = withoutSlash.substring(withoutSlash.lastIndexOf('/') + 1);
            if (!name.equals(rootName)) {
                throw AgentSkillException.invalidPackage(
                        "SKILL.md name must match its package directory: expected " + rootName);
            }
        }
    }

    private void validateOptionalFrontmatter(Map<String, Object> frontmatter) {
        requireOptionalString(frontmatter, "compatibility");
        String compatibility = optionalText(frontmatter.get("compatibility"));
        if (compatibility != null && compatibility.length() > 500) {
            throw AgentSkillException.invalidPackage("SKILL.md compatibility must not exceed 500 characters");
        }
        requireOptionalString(frontmatter, "license");
        String license = optionalText(frontmatter.get("license"));
        if (license != null && license.length() > 512) {
            throw AgentSkillException.invalidPackage("SKILL.md license must not exceed 512 characters");
        }
        if (frontmatter.containsKey("metadata")) {
            Object metadata = frontmatter.get("metadata");
            if (!(metadata instanceof Map<?, ?> values)) {
                throw AgentSkillException.invalidPackage("SKILL.md metadata must be a string-to-string map");
            }
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                    throw AgentSkillException.invalidPackage("SKILL.md metadata must be a string-to-string map");
                }
            }
        }
        if (frontmatter.containsKey("allowed-tools")
                && !(frontmatter.get("allowed-tools") instanceof String)) {
            throw AgentSkillException.invalidPackage("SKILL.md allowed-tools must be a string when provided");
        }
    }

    private void requireOptionalString(Map<String, Object> frontmatter, String field) {
        if (frontmatter.containsKey(field) && !(frontmatter.get(field) instanceof String)) {
            throw AgentSkillException.invalidPackage(
                    "SKILL.md " + field + " must be a string when provided");
        }
    }

    private CodexHostMetadataInspection inspectCodexHostMetadata(Map<String, byte[]> files) {
        byte[] content = files.get(CODEX_HOST_METADATA_PATH);
        if (content == null) {
            return CodexHostMetadataInspection.absent();
        }
        if (content.length > MAX_HOST_METADATA_BYTES) {
            return CodexHostMetadataInspection.invalid("HOST_METADATA_TOO_LARGE");
        }

        String yamlText;
        try {
            yamlText = decodeUtf8(content, CODEX_HOST_METADATA_PATH);
        } catch (AgentSkillException malformedUtf8) {
            return CodexHostMetadataInspection.invalid("INVALID_UTF8");
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(MAX_HOST_METADATA_BYTES);
        try {
            Object parsed = new Yaml(new SafeConstructor(options)).load(yamlText);
            if (!(parsed instanceof Map<?, ?> metadata)) {
                return CodexHostMetadataInspection.invalid("ROOT_NOT_OBJECT");
            }

            boolean allowImplicitInvocation = true;
            Object policyValue = metadata.get("policy");
            if (policyValue != null) {
                if (!(policyValue instanceof Map<?, ?> policy)) {
                    return CodexHostMetadataInspection.invalid("POLICY_NOT_OBJECT");
                }
                Object implicitValue = policy.get("allow_implicit_invocation");
                if (implicitValue != null) {
                    if (!(implicitValue instanceof Boolean allowed)) {
                        return CodexHostMetadataInspection.invalid("IMPLICIT_POLICY_NOT_BOOLEAN");
                    }
                    allowImplicitInvocation = allowed;
                }
            }

            List<Map<String, Object>> toolDependencies = new ArrayList<>();
            Object dependenciesValue = metadata.get("dependencies");
            if (dependenciesValue != null) {
                if (!(dependenciesValue instanceof Map<?, ?> dependencies)) {
                    return CodexHostMetadataInspection.invalid("DEPENDENCIES_NOT_OBJECT");
                }
                Object toolsValue = dependencies.get("tools");
                if (toolsValue != null) {
                    if (!(toolsValue instanceof List<?> tools)) {
                        return CodexHostMetadataInspection.invalid("TOOL_DEPENDENCIES_NOT_LIST");
                    }
                    if (tools.size() > MAX_HOST_TOOL_DEPENDENCIES) {
                        return CodexHostMetadataInspection.invalid("TOO_MANY_TOOL_DEPENDENCIES");
                    }
                    for (Object toolValue : tools) {
                        if (!(toolValue instanceof Map<?, ?> tool)) {
                            return CodexHostMetadataInspection.invalid("TOOL_DEPENDENCY_NOT_OBJECT");
                        }
                        Map<String, Object> summary = new LinkedHashMap<>();
                        List<String> extensionFields = new ArrayList<>();
                        for (Map.Entry<?, ?> entry : tool.entrySet()) {
                            if (!(entry.getKey() instanceof String key) || !StringUtils.hasText(key)) {
                                return CodexHostMetadataInspection.invalid("TOOL_DEPENDENCY_KEY_INVALID");
                            }
                            if (!CODEX_TOOL_DEPENDENCY_FIELDS.contains(key)) {
                                extensionFields.add(key);
                                continue;
                            }
                            if (!(entry.getValue() instanceof String text)) {
                                return CodexHostMetadataInspection.invalid("TOOL_DEPENDENCY_FIELD_NOT_STRING");
                            }
                            String normalized = text.trim();
                            if (normalized.length() > MAX_HOST_METADATA_VALUE_CHARS) {
                                return CodexHostMetadataInspection.invalid("TOOL_DEPENDENCY_FIELD_TOO_LONG");
                            }
                            if (StringUtils.hasText(normalized)) {
                                summary.put(key, normalized);
                            }
                        }
                        if (!extensionFields.isEmpty()) {
                            extensionFields.sort(String::compareTo);
                            summary.put("extensionFields", List.copyOf(extensionFields));
                        }
                        toolDependencies.add(Collections.unmodifiableMap(summary));
                    }
                }
            }
            return CodexHostMetadataInspection.valid(
                    allowImplicitInvocation,
                    List.copyOf(toolDependencies));
        } catch (YAMLException invalidYaml) {
            return CodexHostMetadataInspection.invalid("INVALID_YAML");
        }
    }

    private String normalizeArchivePath(String raw) {
        if (!StringUtils.hasText(raw) || raw.indexOf('\0') >= 0) {
            throw AgentSkillException.invalidPackage("Skill ZIP contains an invalid entry name");
        }
        String path = raw.replace('\\', '/');
        if (path.startsWith("/") || WINDOWS_DRIVE.matcher(path).matches()) {
            throw AgentSkillException.invalidPackage("Skill ZIP contains an absolute path: " + raw);
        }
        if (path.contains("//")) {
            throw AgentSkillException.invalidPackage("Skill ZIP contains an empty path segment: " + raw);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw AgentSkillException.invalidPackage("Skill ZIP contains path traversal: " + raw);
            }
            validatePortableSegment(segment, raw);
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw AgentSkillException.invalidPackage("Skill ZIP contains an empty path");
        }
        String normalized = String.join("/", segments);
        if (normalized.length() > 1024) {
            throw AgentSkillException.invalidPackage("Skill ZIP path exceeds the portable path limit: " + raw);
        }
        return normalized;
    }

    private void validatePortableSegment(String segment, String raw) {
        if (segment.endsWith(".") || segment.endsWith(" ")
                || WINDOWS_FORBIDDEN.matcher(segment).find()
                || segment.chars().anyMatch(value -> value < 32 || value == 127)
                || segment.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw AgentSkillException.invalidPackage("Skill ZIP contains a non-portable path: " + raw);
        }
        String basename = segment.contains(".") ? segment.substring(0, segment.indexOf('.')) : segment;
        if (WINDOWS_RESERVED_NAMES.contains(basename.toUpperCase(Locale.ROOT))) {
            throw AgentSkillException.invalidPackage("Skill ZIP contains a reserved filesystem path: " + raw);
        }
    }

    private String portablePathKey(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private boolean isPackagingNoise(String path) {
        return path.startsWith("__MACOSX/") || path.endsWith("/.DS_Store") || ".DS_Store".equals(path);
    }

    private FileEntry fileEntry(String path, byte[] content) {
        String lower = path.toLowerCase(Locale.ROOT);
        FileKind kind;
        if ("SKILL.md".equals(path)) {
            kind = FileKind.INSTRUCTIONS;
        } else if (path.startsWith("references/")) {
            kind = FileKind.REFERENCE;
        } else if (path.startsWith("assets/") || path.startsWith("templates/") || path.startsWith("examples/")) {
            kind = FileKind.ASSET;
        } else if (path.startsWith("scripts/") || EXECUTABLE_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            kind = FileKind.SCRIPT;
        } else if (path.startsWith("agents/")) {
            kind = FileKind.HOST_METADATA;
        } else {
            kind = FileKind.OTHER;
        }
        return new FileEntry(path, content.length, sha256(content), kind);
    }

    private String declaredVersion(Map<String, Object> frontmatter) {
        Object metadata = frontmatter.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            return optionalText(map.get("version"));
        }
        return null;
    }

    private String requiredText(Object value, String field) {
        String text = optionalText(value);
        if (!StringUtils.hasText(text)) {
            throw AgentSkillException.invalidPackage("SKILL.md " + field + " is required and must be a string");
        }
        return text;
    }

    private String optionalText(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    private String decodeUtf8(byte[] value, String path) {
        if (value == null) {
            throw AgentSkillException.invalidPackage(path + " is missing");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw AgentSkillException.invalidPackage(path + " must be valid UTF-8");
        }
    }

    private String previewText(byte[] value) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
            boolean binaryControls = text.chars().anyMatch(character ->
                    (character < 32 && character != '\n' && character != '\r' && character != '\t')
                            || character == 127);
            return binaryControls ? null : text;
        } catch (CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    private String treeSha256(List<FileEntry> files) {
        MessageDigest digest = sha256Digest();
        for (FileEntry file : files) {
            digest.update(file.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.sha256().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(Long.toString(file.size()).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(byte[] value) {
        return HexFormat.of().formatHex(sha256Digest().digest(value));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long positive(long value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException("reachai.skill." + property + " must be positive");
        }
        return value;
    }

    public enum FileKind {
        INSTRUCTIONS,
        REFERENCE,
        ASSET,
        SCRIPT,
        HOST_METADATA,
        OTHER
    }

    public record FileEntry(String path, long size, String sha256, FileKind kind) {
    }

    public record PackageFilePreview(
            String path,
            long size,
            String sha256,
            FileKind kind,
            boolean previewable,
            boolean truncated,
            String content) {
    }

    public record BundleDiscovery(
            String schema,
            String bundleSourceSha256,
            int archiveFileCount,
            int candidateCount,
            boolean multiSkill,
            List<BundleCandidate> candidates) {
    }

    public record BundleCandidate(
            String sourceRoot,
            boolean selectable,
            String name,
            String description,
            String declaredVersion,
            String license,
            String compatibility,
            boolean hasScripts,
            int fileCount,
            String selectedSourceSha256,
            String contentTreeSha256,
            List<String> warnings,
            String errorCode,
            String errorMessage) {

        static BundleCandidate valid(String sourceRoot, PackageInspection inspection) {
            return new BundleCandidate(
                    sourceRoot,
                    true,
                    inspection.name(),
                    inspection.description(),
                    inspection.declaredVersion(),
                    inspection.license(),
                    inspection.compatibility(),
                    inspection.hasScripts(),
                    inspection.files().size(),
                    inspection.sourceSha256(),
                    inspection.contentTreeSha256(),
                    inspection.warnings(),
                    null,
                    null);
        }

        static BundleCandidate invalid(String sourceRoot,
                                       String errorCode,
                                       String errorMessage) {
            return new BundleCandidate(
                    sourceRoot,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    0,
                    null,
                    null,
                    List.of(),
                    errorCode,
                    errorMessage);
        }
    }

    public record SelectedPackage(
            String sourceRoot,
            String bundleSourceSha256,
            byte[] archive,
            PackageInspection inspection) {

        public SelectedPackage {
            archive = archive.clone();
        }

        @Override
        public byte[] archive() {
            return archive.clone();
        }
    }

    public record PackageLimits(
            long maxPackageBytes,
            long maxExpandedBytes,
            long maxSingleFileBytes,
            int maxFiles,
            int maxInstructionBytes) {
    }

    public record BundleLimits(
            long maxArchiveBytes,
            long maxExpandedBytes,
            long maxSingleFileBytes,
            int maxFiles) {
    }

    public record PackageInspection(
            String name,
            String description,
            String license,
            String compatibility,
            String declaredVersion,
            String sourceSha256,
            String contentTreeSha256,
            String sourceRoot,
            Map<String, Object> frontmatter,
            List<FileEntry> files,
            List<String> scriptFiles,
            List<String> extensionFields,
            CodexHostMetadataInspection codexHostMetadata,
            List<String> warnings) {

        public boolean hasScripts() {
            return !scriptFiles.isEmpty();
        }

        public Map<String, Object> manifest() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schema", "reachai.agent-skill-package-manifest.v1");
            value.put("name", name);
            value.put("sourceRoot", sourceRoot);
            value.put("sourceSha256", sourceSha256);
            value.put("contentTreeSha256", contentTreeSha256);
            value.put("files", files);
            return value;
        }

        public Map<String, Object> validationReport() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schema", "reachai.agent-skill-validation.v1");
            value.put("formatCompatible", true);
            value.put("errors", List.of());
            value.put("warnings", warnings);
            value.put("extensionFields", extensionFields);
            return value;
        }

        public Map<String, Object> riskReport() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schema", "reachai.agent-skill-risk.v1");
            value.put("level", hasScripts() ? "SCRIPT_REVIEW_REQUIRED" : "LOW");
            value.put("hasScripts", hasScripts());
            value.put("scriptFiles", scriptFiles);
            value.put("allowedToolsDeclared", frontmatter.containsKey("allowed-tools"));
            value.put("codexToolDependenciesDeclared", codexHostMetadata.toolDependencyCount());
            return value;
        }

        public Map<String, Object> compatibilityReport() {
            Map<String, Object> hosts = new LinkedHashMap<>();
            hosts.put("AGENTSCOPE", hostCompatibility(
                    "COMPATIBLE", "IMPLEMENTED", "UNIT_VERIFIED"));
            Map<String, Object> codex = hostCompatibility(
                    codexHostMetadata.valid() ? "COMPATIBLE" : "INCOMPATIBLE",
                    "NOT_IMPLEMENTED",
                    codexHostMetadata.valid() ? "FORMAT_VERIFIED" : "HOST_METADATA_INVALID");
            codex.put("hostMetadata", codexHostMetadata.report());
            hosts.put("CODEX", codex);
            hosts.put("OPENCODE", hostCompatibility(
                    "COMPATIBLE", "NOT_IMPLEMENTED", "FORMAT_VERIFIED"));
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schema", "reachai.agent-skill-compatibility.v3");
            value.put("format", "COMPATIBLE");
            value.put("dependency", !codexHostMetadata.valid()
                    ? "HOST_METADATA_INVALID"
                    : codexHostMetadata.toolDependencyCount() > 0
                            ? "DECLARED_NOT_RESOLVED"
                            : "NONE_DECLARED");
            value.put("policy", hasScripts() ? "REVIEW_REQUIRED" : "COMPATIBLE");
            value.put("hosts", hosts);
            value.put("hostE2e", "NOT_RUN");
            return value;
        }

        private Map<String, Object> hostCompatibility(String format,
                                                      String adapter,
                                                      String evidence) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("format", format);
            value.put("adapter", adapter);
            value.put("evidence", evidence);
            return value;
        }
    }

    public record CodexHostMetadataInspection(
            String status,
            Boolean allowImplicitInvocation,
            List<Map<String, Object>> toolDependencies,
            String errorCode) {

        static CodexHostMetadataInspection absent() {
            return new CodexHostMetadataInspection("ABSENT", null, List.of(), null);
        }

        static CodexHostMetadataInspection valid(boolean allowImplicitInvocation,
                                                  List<Map<String, Object>> toolDependencies) {
            return new CodexHostMetadataInspection(
                    "VALID", allowImplicitInvocation, List.copyOf(toolDependencies), null);
        }

        static CodexHostMetadataInspection invalid(String errorCode) {
            return new CodexHostMetadataInspection("INVALID", null, List.of(), errorCode);
        }

        public boolean present() {
            return !"ABSENT".equals(status);
        }

        public boolean valid() {
            return !"INVALID".equals(status);
        }

        public int toolDependencyCount() {
            return toolDependencies.size();
        }

        public Map<String, Object> report() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schema", "reachai.codex-skill-host-metadata.v1");
            value.put("path", CODEX_HOST_METADATA_PATH);
            value.put("status", status);
            value.put("allowImplicitInvocation", allowImplicitInvocation);
            value.put("toolDependencyCount", toolDependencyCount());
            value.put("toolDependencies", toolDependencies);
            if (errorCode != null) {
                value.put("errorCode", errorCode);
            }
            return value;
        }
    }
}
