package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.mcp.application.port.AiToolConnectionRepository;
import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection.ConnectedSystem;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Employee-side lifecycle of personal AI-tool connections: which business
 * systems an employee may tick, creating a connection (key shown once), listing
 * and disconnecting. The owner always comes from attested business-system
 * identity, never from request fields.
 */
@Service
public class AiToolConnectionService {

    static final String KEY_PREFIX = "rai_";

    private final AiToolConnectionRepository connectionRepository;
    private final McpEmployeeAccessRepository accessRepository;
    private final McpPublicationRepository publicationRepository;
    private final BusinessUserSyncService businessUserSyncService;
    private final McpHubProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiToolConnectionService(AiToolConnectionRepository connectionRepository,
                                   McpEmployeeAccessRepository accessRepository,
                                   McpPublicationRepository publicationRepository,
                                   BusinessUserSyncService businessUserSyncService,
                                   McpHubProperties properties) {
        this.connectionRepository = connectionRepository;
        this.accessRepository = accessRepository;
        this.publicationRepository = publicationRepository;
        this.businessUserSyncService = businessUserSyncService;
        this.properties = properties;
    }

    /** Systems the employee may tick: the current one plus systems they also have an account in. */
    public List<SystemOption> options(EmployeeOwner owner) {
        Set<String> linked = linkedSystems(owner);
        List<SystemOption> options = new ArrayList<>();
        for (McpEmployeeAccess access : accessRepository.findEnabled()) {
            if (!linked.contains(access.projectCode())) {
                continue;
            }
            publishedTools(access).ifPresent(toolCount -> options.add(new SystemOption(
                    access.projectCode(),
                    access.label(),
                    toolCount,
                    access.projectCode().equals(owner.projectCode()))));
        }
        options.sort((a, b) -> Boolean.compare(b.current(), a.current()));
        return options;
    }

    public List<ConnectionView> list(EmployeeOwner owner) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> labels = systemLabels();
        return connectionRepository.findByOwner(owner.tenantId(), owner.businessUserId()).stream()
                .map(connection -> view(connection, labels, now))
                .toList();
    }

    @Transactional
    public CreatedConnection create(EmployeeOwner owner, String toolKind, List<String> projectCodes) {
        AiToolKind kind = parseKind(toolKind);
        Set<String> requested = new LinkedHashSet<>();
        for (String code : projectCodes == null ? List.<String>of() : projectCodes) {
            if (code != null && !code.isBlank()) {
                requested.add(code.trim());
            }
        }
        if (requested.isEmpty()) {
            throw new McpDomainException("AI_TOOL_CONNECTION_NO_SYSTEM", "tick at least one business system");
        }
        Map<String, SystemOption> allowed = options(owner).stream()
                .collect(Collectors.toMap(SystemOption::projectCode, Function.identity()));
        List<ConnectedSystem> systems = new ArrayList<>();
        for (String code : requested) {
            if (!allowed.containsKey(code)) {
                throw new McpDomainException("AI_TOOL_CONNECTION_SYSTEM_NOT_AVAILABLE",
                        "business system is not available to this employee: " + code);
            }
            McpEmployeeAccess access = accessRepository.findByProjectCode(code).orElseThrow();
            systems.add(new ConnectedSystem(code, access.publicationId()));
        }
        LocalDateTime now = LocalDateTime.now();
        if (connectionRepository.countActiveByOwner(owner.tenantId(), owner.businessUserId(), now)
                >= properties.getMaxEmployeeConnections()) {
            throw new McpDomainException("AI_TOOL_CONNECTION_LIMIT",
                    "too many active connections; disconnect an unused one first");
        }
        String secret = generateKey();
        AiToolConnection created = connectionRepository.create(new AiToolConnection(
                null, owner.tenantId(), owner.businessUserId(), owner.appId(), owner.externalUserId(),
                owner.userName(), kind, secret.substring(0, 12), AiToolConnectionStatus.ACTIVE,
                now.plusDays(properties.getEmployeeConnectionTtlDays()), null, now, systems), sha256Hex(secret));
        return new CreatedConnection(view(created, systemLabels(), now), secret);
    }

    @Transactional
    public ConnectionView revoke(EmployeeOwner owner, long connectionId) {
        AiToolConnection connection = connectionRepository.findById(connectionId)
                .filter(found -> found.ownedBy(owner.tenantId(), owner.businessUserId()))
                .orElseThrow(() -> new McpDomainException("AI_TOOL_CONNECTION_NOT_FOUND",
                        "connection not found"));
        LocalDateTime now = LocalDateTime.now();
        connectionRepository.revoke(connectionId, now);
        return view(connectionRepository.findById(connectionId).orElse(connection), systemLabels(), now);
    }

    private Set<String> linkedSystems(EmployeeOwner owner) {
        Set<String> linked = new LinkedHashSet<>();
        linked.add(owner.projectCode());
        linked.add(owner.appId());
        linked.addAll(businessUserSyncService.activeAppIds(owner.tenantId(), owner.businessUserId()));
        return linked;
    }

    /** Tool count of the publication's live revision, or empty when it is not published. */
    private Optional<Integer> publishedTools(McpEmployeeAccess access) {
        Optional<McpPublication> publication = publicationRepository.findById(access.publicationId());
        if (publication.isEmpty() || publication.get().state() != McpPublicationStatus.PUBLISHED
                || publication.get().currentRevisionId() == null) {
            return Optional.empty();
        }
        return publicationRepository.findRevision(access.publicationId(), publication.get().currentRevisionId())
                .map(revision -> revision.tools().size());
    }

    private Map<String, String> systemLabels() {
        return accessRepository.findEnabled().stream()
                .collect(Collectors.toMap(McpEmployeeAccess::projectCode, McpEmployeeAccess::label, (a, b) -> a));
    }

    private ConnectionView view(AiToolConnection connection, Map<String, String> labels, LocalDateTime now) {
        String state = connection.state() == AiToolConnectionStatus.ACTIVE && connection.expired(now)
                ? AiToolConnectionStatus.EXPIRED.name()
                : connection.state().name();
        return new ConnectionView(
                connection.id(),
                connection.toolKind().name(),
                connection.keyPrefix(),
                state,
                connection.systems().stream()
                        .map(system -> new ConnectedSystemView(system.projectCode(),
                                labels.getOrDefault(system.projectCode(), system.projectCode())))
                        .toList(),
                connection.createdAt(),
                connection.lastUsedAt(),
                connection.expiresAt());
    }

    private static AiToolKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return AiToolKind.OTHER;
        }
        try {
            return AiToolKind.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException invalid) {
            throw new McpDomainException("AI_TOOL_KIND_INVALID", "unknown AI tool: " + value);
        }
    }

    private String generateKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    /** The synced employee behind a verified business-system session. */
    public record EmployeeOwner(String tenantId, Long businessUserId, String appId, String projectCode,
                                String externalUserId, String userName, List<String> roles) {
    }

    public record SystemOption(String projectCode, String name, int toolCount, boolean current) {
    }

    public record ConnectedSystemView(String projectCode, String name) {
    }

    public record ConnectionView(Long id, String toolKind, String keyPrefix, String state,
                                 List<ConnectedSystemView> systems, LocalDateTime createdAt,
                                 LocalDateTime lastUsedAt, LocalDateTime expiresAt) {
    }

    public record CreatedConnection(ConnectionView connection, String secret) {
    }
}
