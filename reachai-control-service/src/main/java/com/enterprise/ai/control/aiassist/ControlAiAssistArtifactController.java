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

    @GetMapping("/go-sdk/{filename}")
    public ResponseEntity<byte[]> downloadGoSdk(@PathVariable("filename") String filename) throws IOException {
        boolean digest = filename != null && filename.endsWith(".sha256");
        String packageFilename = digest ? filename.substring(0, filename.length() - ".sha256".length()) : filename;
        return ControlGoSdkArtifactSupport.packageResponse(packageFilename, digest);
    }

    @GetMapping("/node-sdk/{filename}")
    public ResponseEntity<byte[]> downloadNodeSdk(@PathVariable("filename") String filename) throws IOException {
        boolean digest = filename != null && filename.endsWith(".sha256");
        String packageFilename = digest ? filename.substring(0, filename.length() - ".sha256".length()) : filename;
        return ControlNodeSdkArtifactSupport.packageResponse(packageFilename, digest);
    }

    @GetMapping("/python-sdk/{filename}")
    public ResponseEntity<byte[]> downloadPythonSdk(@PathVariable("filename") String filename) throws IOException {
        boolean digest = filename != null && filename.endsWith(".sha256");
        String packageFilename = digest ? filename.substring(0, filename.length() - ".sha256".length()) : filename;
        return ControlPythonSdkArtifactSupport.packageResponse(packageFilename, digest);
    }

    @GetMapping("/dotnet-sdk/{packageName}/{version}.nupkg")
    public ResponseEntity<byte[]> downloadDotnetSdk(@PathVariable("packageName") String packageName,
                                                     @PathVariable("version") String version) throws IOException {
        return ControlDotnetSdkArtifactSupport.packageResponse(packageName, version);
    }

    @GetMapping("/dotnet-sdk/{packageName}/{version}.nupkg.sha256")
    public ResponseEntity<byte[]> downloadDotnetSdkSha256(@PathVariable("packageName") String packageName,
                                                           @PathVariable("version") String version) throws IOException {
        return ControlDotnetSdkArtifactSupport.sha256Response(packageName, version);
    }

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
