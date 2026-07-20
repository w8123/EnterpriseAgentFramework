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
}
