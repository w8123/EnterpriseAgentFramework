package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryMemberEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryMemberMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Project-locked preparation/commit token; no database connection is held during scanner IO. */
@Service
@RequiredArgsConstructor
public class CapabilityScanRevisionService {
    private final ScanProjectToolMapper observations;
    private final HttpApiInventoryStateMapper inventories;
    private final HttpApiInventoryMemberMapper members;
    private final HttpApiSourceBindingMapper bindings;

    public String revision(ScanProjectEntity project) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, project.getId(), project.getProjectCode(), project.getEnvironment(), project.getName(),
                    project.getProjectKind(), project.getBaseUrl(), project.getContextPath(), project.getScanPath(),
                    project.getScanType(), project.getSpecFile(), project.getScanSettings(), project.getStatus(),
                    project.getLastScannedAt(), project.getUpdateTime(), project.getAuthType(), project.getAuthApiKeyIn(),
                    project.getAuthApiKeyName(), project.getAuthApiKeyValue());
            for (var row : observations.selectList(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId()).orderByAsc(ScanProjectToolEntity::getId))) {
                add(digest, row.getId(), row.getModuleId(), row.getName(), row.getTitle(), row.getDescription(),
                        row.getParametersJson(), row.getSource(), row.getSourceLocation(), row.getSourceQualifiedName(),
                        row.getAssetType(), row.getHttpMethod(), row.getBaseUrl(), row.getContextPath(), row.getEndpointPath(),
                        row.getRequestBodyType(), row.getResponseType(), row.getCapabilityMetadataJson(), row.getEnabled(),
                        row.getGlobalToolDefinitionId(), row.getRemovedFromSource(), row.getRemovedAt(), row.getUpdateTime());
            }
            for (var row : inventories.selectList(Wrappers.<HttpApiInventoryStateEntity>lambdaQuery()
                    .eq(HttpApiInventoryStateEntity::getProjectId, project.getId()).orderByAsc(HttpApiInventoryStateEntity::getId))) {
                add(digest, row.getId(), row.getProjectCode(), row.getEnvironment(), row.getSourceKind(), row.getInventoryToken(),
                        row.getSupported(), row.getComplete(), row.getObservedAt());
            }
            var sources = bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                    .eq(HttpApiSourceBindingEntity::getProjectId, project.getId()).orderByAsc(HttpApiSourceBindingEntity::getId));
            for (var row : sources) {
                add(digest, row.getId(), row.getAssetId(), row.getSourceKind(), row.getSourceKey(), row.getSourceRevision(),
                        row.getSourceContractHash(), row.getStatus(), row.getObservedAt());
            }
            if (!sources.isEmpty()) {
                for (var row : members.selectList(Wrappers.<HttpApiInventoryMemberEntity>lambdaQuery()
                        .in(HttpApiInventoryMemberEntity::getBindingId, sources.stream().map(HttpApiSourceBindingEntity::getId).toList())
                        .orderByAsc(HttpApiInventoryMemberEntity::getId))) {
                    add(digest, row.getId(), row.getBindingId(), row.getInventoryToken(), row.getObservedAt());
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void add(MessageDigest digest, Object... fields) {
        for (Object field : fields) {
            // Length framing preserves distinctions between null, delimiters, and empty strings.
            byte[] bytes = String.valueOf(field).getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(field == null ? -1 : bytes.length).array());
            if (field != null) digest.update(bytes);
        }
    }
}
