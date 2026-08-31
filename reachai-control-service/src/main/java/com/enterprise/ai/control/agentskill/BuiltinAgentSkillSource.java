package com.enterprise.ai.control.agentskill;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class BuiltinAgentSkillSource {

    private static final String BASE = "ai-assist/skills/";
    private static final Map<String, Descriptor> DESCRIPTORS = Map.of(
            "reachai-onboarding", new Descriptor(
                    "reachai-onboarding", "0.6.0", "ReachAI SDK onboarding skill for AI coding tools."),
            "workflow-ai-coding", new Descriptor(
                    "workflow-ai-coding", "0.1.0", "ReachAI Workflow AI Coding skill for editing, validating, and debugging workflow drafts."));

    private final PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();

    public List<Descriptor> descriptors() {
        return DESCRIPTORS.values().stream()
                .sorted(Comparator.comparing(Descriptor::name))
                .toList();
    }

    public Descriptor descriptor(String name) {
        Descriptor descriptor = DESCRIPTORS.get(name);
        if (descriptor == null) {
            throw AgentSkillException.notFound("Built-in Skill not found: " + name);
        }
        return descriptor;
    }

    public byte[] packageBytes(String name) {
        Descriptor descriptor = descriptor(name);
        String root = BASE + descriptor.name() + "/";
        try {
            Map<String, Resource> discovered = new LinkedHashMap<>();
            for (String pattern : List.of("classpath*:" + root + "*", "classpath*:" + root + "**/*")) {
                for (Resource resource : resources.getResources(pattern)) {
                    if (!resource.isReadable()) {
                        continue;
                    }
                    String relative = relativePath(resource, root);
                    if (relative != null && !relative.endsWith("/")) {
                        discovered.putIfAbsent(relative, resource);
                    }
                }
            }
            if (!discovered.containsKey("SKILL.md")) {
                throw AgentSkillException.notFound("Built-in Skill SKILL.md is missing: " + name);
            }
            List<Map.Entry<String, Resource>> files = new ArrayList<>(discovered.entrySet());
            files.sort(Map.Entry.comparingByKey());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, Resource> file : files) {
                    byte[] content;
                    try (var input = file.getValue().getInputStream()) {
                        content = input.readAllBytes();
                    }
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    ZipEntry entry = new ZipEntry(name + "/" + file.getKey());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(content.length);
                    entry.setCompressedSize(content.length);
                    entry.setCrc(crc.getValue());
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                    zip.putNextEntry(entry);
                    zip.write(content);
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (AgentSkillException expected) {
            throw expected;
        } catch (IOException exception) {
            throw AgentSkillException.artifactFailure("Built-in Skill could not be packaged: " + name, exception);
        }
    }

    private String relativePath(Resource resource, String root) throws IOException {
        String uri = resource.getURI().toString().replace('\\', '/');
        int rootIndex = uri.lastIndexOf(root);
        if (rootIndex < 0) {
            return null;
        }
        String relative = uri.substring(rootIndex + root.length());
        return URLDecoder.decode(relative, StandardCharsets.UTF_8);
    }

    public record Descriptor(String name, String version, String description) {
    }
}
