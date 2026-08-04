package com.enterprise.ai.control.aiassist;

import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-assist/artifacts")
public class ControlAiAssistArtifactController {

    @GetMapping("/embed-chat/{version}.tgz")
    public ResponseEntity<byte[]> downloadEmbedChat(@PathVariable("version") String version) throws IOException {
        return ControlEmbedChatArtifactSupport.tarballResponse(version);
    }

    @GetMapping("/embed-chat/{version}.sha256")
    public ResponseEntity<byte[]> downloadEmbedChatSha256(@PathVariable("version") String version) throws IOException {
        return ControlEmbedChatArtifactSupport.sha256Response(version);
    }

    @GetMapping("/java-sdk/{artifactId}/{version}.jar")
    public ResponseEntity<byte[]> downloadJavaSdkJar(
            @PathVariable("artifactId") String artifactId,
            @PathVariable("version") String version) throws IOException {
        return ControlJavaSdkArtifactSupport.artifactResponse(
                artifactId,
                version,
                "jar");
    }

    @GetMapping("/java-sdk/{artifactId}/{version}.pom")
    public ResponseEntity<byte[]> downloadJavaSdkPom(
            @PathVariable("artifactId") String artifactId,
            @PathVariable("version") String version) throws IOException {
        return ControlJavaSdkArtifactSupport.artifactResponse(
                artifactId,
                version,
                "pom");
    }

    @GetMapping("/java-sdk/{artifactId}/{version}.jar.sha256")
    public ResponseEntity<byte[]> downloadJavaSdkJarSha256(
            @PathVariable("artifactId") String artifactId,
            @PathVariable("version") String version) throws IOException {
        return ControlJavaSdkArtifactSupport.sha256Response(
                artifactId,
                version,
                "jar");
    }

    @GetMapping("/java-sdk/{artifactId}/{version}.pom.sha256")
    public ResponseEntity<byte[]> downloadJavaSdkPomSha256(
            @PathVariable("artifactId") String artifactId,
            @PathVariable("version") String version) throws IOException {
        return ControlJavaSdkArtifactSupport.sha256Response(
                artifactId,
                version,
                "pom");
    }
}
