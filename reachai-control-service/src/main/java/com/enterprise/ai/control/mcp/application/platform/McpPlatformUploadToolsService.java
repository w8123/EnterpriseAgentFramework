package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeImportPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillPackagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tools that take an out-of-band upload. Writes consume the upload before dispatch, so an unconfirmed result can never be
 * replayed with the same file; the caller re-reads the target instead and uploads again only after checking.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformUploadToolsService {
    private static final Set<String> CHUNK_STRATEGIES = Set.of("fixed_length", "paragraph", "semantic");
    private final McpPlatformUploadStore uploads;
    private final McpPlatformKnowledgeImportPort knowledgeImports;
    private final McpPlatformSkillPackagePort skillPackages;

    public ResponseEntity<?> describe(ConnectionIdentity actor, String uploadId) {
        try { return ResponseEntity.ok(uploads.describe(actor, uploadId).view()); }
        catch (ResponseStatusException missing) { return ResponseEntity.status(missing.getStatusCode()).build(); }
    }

    public ResponseEntity<?> submitKnowledgeImport(ConnectionIdentity actor, String uploadId, String knowledgeBaseCode,
                                                   String chunkStrategy, int chunkSize, int chunkOverlap) {
        String strategy = chunkStrategy == null ? "fixed_length" : chunkStrategy;
        if (!CHUNK_STRATEGIES.contains(strategy) || chunkSize < 50 || chunkSize > 4000 || chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            return ResponseEntity.badRequest().build();
        }
        McpPlatformUploadStore.Upload upload;
        byte[] content;
        try {
            upload = uploads.describe(actor, uploadId);
            content = uploads.read(actor, uploadId, true);
        } catch (ResponseStatusException missing) { return ResponseEntity.status(missing.getStatusCode()).build(); }
        ResponseEntity<?> response;
        try { response = knowledgeImports.submit(actor.session(), upload.fileName(), content, knowledgeBaseCode, strategy, chunkSize, chunkOverlap); }
        catch (ResponseStatusException refused) { return ResponseEntity.status(refused.getStatusCode()).build(); }
        catch (RuntimeException lost) { response = null; }
        if (response != null && response.getStatusCode().is2xxSuccessful()) return response;
        if (response != null && response.getStatusCode().is4xxClientError()) return ResponseEntity.status(response.getStatusCode()).build();
        return unknown(upload, "知识库导入任务是否已创建未确认，请先查看知识库文件列表；确认没有后再重新上传并提交。");
    }

    public ResponseEntity<?> discoverSkillPackage(ConnectionIdentity actor, String uploadId) {
        try { return skillPackages.discover(actor.session(), uploads.read(actor, uploadId, false)); }
        catch (ResponseStatusException refused) { return ResponseEntity.status(refused.getStatusCode()).build(); }
    }

    public ResponseEntity<?> importSkillPackage(ConnectionIdentity actor, String uploadId, String publisher, String version,
                                                String displayName, String visibility, String projectCode, String sourceRoot) {
        McpPlatformUploadStore.Upload upload;
        byte[] archive;
        try {
            upload = uploads.describe(actor, uploadId);
            archive = uploads.read(actor, uploadId, true);
        } catch (ResponseStatusException missing) { return ResponseEntity.status(missing.getStatusCode()).build(); }
        ResponseEntity<?> response;
        try { response = skillPackages.importPackage(actor.session(), upload.fileName(), archive, publisher, version, displayName, visibility, projectCode, sourceRoot); }
        catch (ResponseStatusException refused) { return ResponseEntity.status(refused.getStatusCode()).build(); }
        catch (RuntimeException lost) { response = null; }
        if (response != null && response.getStatusCode().is2xxSuccessful()) return response;
        if (response != null && response.getStatusCode().is4xxClientError()) return ResponseEntity.status(response.getStatusCode()).build();
        return unknown(upload, "Skill 包是否已导入未确认，请先用 reachai_skills_list 查找；确认没有后再重新上传并导入。");
    }

    private static ResponseEntity<?> unknown(McpPlatformUploadStore.Upload upload, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNKNOWN"); body.put("fileName", upload.fileName()); body.put("sha256", upload.sha256()); body.put("message", message);
        return ResponseEntity.accepted().body(body);
    }
}
