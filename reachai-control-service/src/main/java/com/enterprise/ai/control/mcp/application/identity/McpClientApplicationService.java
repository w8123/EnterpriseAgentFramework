package com.enterprise.ai.control.mcp.application.identity;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.mcp.application.port.McpClientRepository;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import com.enterprise.ai.control.mcp.domain.client.McpClientStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MCP Client credential lifecycle bound to exactly one publication. The
 * plaintext API key is generated once and returned once; only its SHA-256
 * hash is ever persisted.
 */
@Service
public class McpClientApplicationService {

    private final McpClientRepository clientRepository;
    private final McpPublicationApplicationService publicationService;
    private final ControlToolAclDecisionService aclDecisionService;
    private final SecureRandom secureRandom = new SecureRandom();

    public McpClientApplicationService(McpClientRepository clientRepository,
                                       McpPublicationApplicationService publicationService,
                                       ControlToolAclDecisionService aclDecisionService) {
        this.clientRepository = clientRepository;
        this.publicationService = publicationService;
        this.aclDecisionService = aclDecisionService;
    }

    public List<McpClient> list(long publicationId) {
        publicationService.require(publicationId);
        return clientRepository.findByPublication(publicationId);
    }

    @Transactional
    public CreatedClient create(long publicationId, String name,
                                Long projectId, String projectCode, String environment, String tenantId,
                                List<String> roles,
                                List<String> toolScope, LocalDateTime expiresAt) {
        var publication = publicationService.require(publicationId);
        if (publication.state() != com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus.PUBLISHED) {
            throw new McpDomainException("MCP_CLIENT_PUBLICATION_NOT_PUBLISHED",
                    "credentials can only be issued for a published publication");
        }
        Set<String> availableTools = publicationService.availableToolNames(publicationId);
        validateScope(availableTools, toolScope);
        List<String> validatedRoles = validateRoles(roles);
        String plaintextApiKey = generateApiKey();
        McpClient client = clientRepository.save(new McpClient(
                null, publicationId, name, projectId, projectCode, environment, tenantId,
                plaintextApiKey.substring(0, 16),
                sha256Hex(plaintextApiKey),
                validatedRoles,
                toolScope == null ? List.of() : toolScope,
                McpClientStatus.ACTIVE, true, expiresAt, null, null, null));
        return new CreatedClient(client, plaintextApiKey);
    }

    public McpClient update(long publicationId, long clientId, List<String> roles,
                            List<String> toolScope, Boolean enabled, LocalDateTime expiresAt) {
        McpClient current = requireOwned(publicationId, clientId);
        Set<String> availableTools = publicationService.availableToolNames(publicationId);
        validateScope(availableTools, toolScope);
        List<String> validatedRoles = roles == null ? current.roles() : validateRoles(roles);
        McpClient updated = clientRepository.save(current.updateSettings(
                validatedRoles,
                toolScope == null ? current.toolScope() : toolScope,
                enabled == null ? current.enabled() : enabled,
                // PUT uses the submitted expiry as the full replacement value;
                // null explicitly clears an existing expiry ("never expires").
                expiresAt,
                LocalDateTime.now()));
        return updated;
    }

    /** Rotates the credential: the old key becomes ROTATED and a new key is issued. */
    @Transactional
    public CreatedClient rotate(long publicationId, long clientId) {
        McpClient current = requireOwned(publicationId, clientId);
        clientRepository.save(current.rotate(LocalDateTime.now()));
        String plaintextApiKey = generateApiKey();
        McpClient replacement = clientRepository.save(new McpClient(
                null, publicationId, current.name(),
                current.projectId(), current.projectCode(), current.environment(), current.tenantId(),
                plaintextApiKey.substring(0, 16),
                sha256Hex(plaintextApiKey),
                current.roles(), current.toolScope(),
                McpClientStatus.ACTIVE, current.enabled(), current.expiresAt(),
                null, null, null));
        return new CreatedClient(replacement, plaintextApiKey);
    }

    public McpClient revoke(long publicationId, long clientId) {
        McpClient current = requireOwned(publicationId, clientId);
        return clientRepository.save(current.revoke(LocalDateTime.now()));
    }

    public McpClient requireOwned(long publicationId, long clientId) {
        McpClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new McpDomainException("MCP_CLIENT_NOT_FOUND",
                        "client not found: " + clientId));
        if (client.publicationId() != publicationId) {
            throw new McpDomainException("MCP_CLIENT_NOT_FOUND",
                    "client " + clientId + " does not belong to publication " + publicationId);
        }
        return client;
    }

    private void validateScope(Set<String> availableTools, List<String> toolScope) {
        if (toolScope == null || toolScope.isEmpty()) {
            return;
        }
        for (String tool : toolScope) {
            String normalized = tool == null ? "" : tool.trim();
            if (!normalized.isEmpty() && !availableTools.contains(normalized)) {
                throw new McpDomainException("MCP_CLIENT_SCOPE_OUTSIDE_PUBLICATION",
                        "tool scope entry is not offered by the publication: " + normalized);
            }
        }
    }

    private List<String> validateRoles(List<String> roles) {
        List<String> normalized = roles == null ? List.of() : roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new McpDomainException("MCP_CLIENT_ROLE_REQUIRED",
                    "at least one Tool ACL role is required");
        }
        Set<String> known = aclDecisionService.knownRoleCodes();
        for (String role : normalized) {
            if (!known.contains(role)) {
                throw new McpDomainException("MCP_CLIENT_ROLE_UNKNOWN",
                        "role is not managed by Tool ACL: " + role);
            }
        }
        return normalized;
    }

    private String generateApiKey() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return "mcp_" + HexFormat.of().formatHex(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    public record CreatedClient(McpClient client, String plaintextApiKey) {
        public String state() {
            return client.state().name().toLowerCase(Locale.ROOT);
        }
    }
}
