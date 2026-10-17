package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.HttpApiConsolePolicy;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Revisioned API origin/auth binding, independently checked against the Capability owner.
 *
 * <p>Since D27 (2026-10-16) a project + environment default connection is inherited by every API; an API may
 * carry an active override row. The effective connection is the active override, else the project default.
 * Invocations pin the opaque effective revision {@code rowRevision * 2 + (override ? 1 : 0)}: both row
 * revisions only grow (an override is deactivated, never deleted), so any change to the effective source or
 * to its row yields a new effective revision and stale invocations or Workflow pins fail closed.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimeHttpApiConnectionService {
    private static final Set<String> AUTH_MODES = Set.of("NONE", "API_KEY_HEADER", "BEARER");
    private final RuntimeHttpApiConnectionMapper connections;
    private final RuntimeHttpApiProjectConnectionMapper projectConnections;
    private final RuntimeCapabilityCatalogClient capability;
    private final RuntimeWorkflowCredentialService credentials;
    private final WorkflowHttpEgressPolicy egress;
    private RuntimeHttpApiVerificationService verification;

    @Autowired
    public void setVerification(RuntimeHttpApiVerificationService verification) { this.verification = verification; }

    public HttpApiConsoleContracts.ConnectionView read(HttpApiConsoleContracts.ConnectionCommand command) {
        HttpApiConsoleContracts.ExecutionContext owner = owner(command);
        return view(owner);
    }

    /** Saves this API's override; {@code expectedRevision} is the override row revision (null before the first one). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HttpApiConsoleContracts.ConnectionView save(HttpApiConsoleContracts.ConnectionCommand command,
                                                       String actorId) {
        HttpApiConsoleContracts.ExecutionContext owner = owner(command);
        if (command.save() == null || !StringUtils.hasText(actorId)) {
            throw new IllegalArgumentException("connection request is incomplete");
        }
        if (!owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus())
                || !owner.consoleCallSupported()
                || !Objects.equals(owner.acceptedContractHash(), owner.candidateContractHash())) {
            throw new Conflict("HTTP_API_NOT_ACCEPTED", "当前 API 契约或来源尚不能配置试调用");
        }
        HttpApiConsoleContracts.ConnectionSaveRequest save = command.save();
        String origin = origin(save.origin());
        String mode = save.authMode() == null ? "" : save.authMode().toUpperCase(Locale.ROOT);
        RuntimeWorkflowCredentialRuntime credential = selectedCredential(mode, save.credentialRef(), owner);
        String authReason = HttpApiConsolePolicy.connectionAuthReason(owner.acceptedContract(), mode,
                credential == null ? null : credential.type(), credentialHeader(credential));
        if (authReason != null) throw new Conflict("HTTP_API_AUTH_MISMATCH", authReason);
        RuntimeHttpApiConnectionEntity existing = connections.selectByRefForUpdate(owner.qualifiedName());
        if (!sameScope(owner, existing)) {
            throw new Conflict("HTTP_API_OWNER_CHANGED", "连接所属项目或环境与当前 API 不一致，请刷新后核对");
        }
        Long expected = save.expectedRevision();
        if (existing == null ? expected != null : !Objects.equals(existing.getRevision(), expected)) {
            throw new Conflict("HTTP_API_CONNECTION_REVISION_CHANGED", "连接配置已变化，请刷新后比较并重新保存");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (existing == null) {
            existing = new RuntimeHttpApiConnectionEntity();
            existing.setQualifiedName(owner.qualifiedName());
            existing.setProjectId(owner.projectId());
            existing.setProjectCode(owner.projectCode());
            existing.setEnvironment(owner.environment());
            existing.setCreatedAt(now);
            existing.setRevision(1L);
        } else {
            existing.setRevision(existing.getRevision() + 1L);
        }
        existing.setOrigin(origin);
        existing.setAuthMode(mode);
        existing.setCredentialRef("NONE".equals(mode) ? null : save.credentialRef().trim());
        existing.setOverrideActive(true);
        existing.setSavedBy(actorId.trim());
        existing.setUpdatedAt(now);
        try {
            if (existing.getId() == null) connections.insert(existing); else connections.updateById(existing);
        } catch (DuplicateKeyException duplicate) {
            throw new Conflict("HTTP_API_CONNECTION_REVISION_CHANGED", "连接配置已由其他操作保存，请刷新后重试");
        }
        return view(owner);
    }

    /** Reverts this API to the project default; the override row is kept inactive so its revision keeps growing. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HttpApiConsoleContracts.ConnectionView removeOverride(HttpApiConsoleContracts.ConnectionCommand command,
                                                                 String actorId) {
        HttpApiConsoleContracts.ExecutionContext owner = owner(command);
        if (command.save() == null || command.save().expectedRevision() == null || !StringUtils.hasText(actorId)) {
            throw new IllegalArgumentException("override removal request is incomplete");
        }
        RuntimeHttpApiConnectionEntity existing = connections.selectByRefForUpdate(owner.qualifiedName());
        if (!sameScope(owner, existing)) {
            throw new Conflict("HTTP_API_OWNER_CHANGED", "连接所属项目或环境与当前 API 不一致，请刷新后核对");
        }
        if (existing == null || !active(existing)
                || !Objects.equals(existing.getRevision(), command.save().expectedRevision())) {
            throw new Conflict("HTTP_API_CONNECTION_REVISION_CHANGED", "本 API 覆盖连接已变化，请刷新后重试");
        }
        existing.setOverrideActive(false);
        existing.setRevision(existing.getRevision() + 1L);
        existing.setSavedBy(actorId.trim());
        existing.setUpdatedAt(LocalDateTime.now().withNano(0));
        connections.updateById(existing);
        return view(owner);
    }

    /**
     * Saves the project + environment default resolved from this API's verified owner. The API contract is not
     * required to match: each inheriting API reports its own auth mismatch and can override.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HttpApiConsoleContracts.ConnectionView saveProjectDefault(HttpApiConsoleContracts.ConnectionCommand command,
                                                                     String actorId) {
        HttpApiConsoleContracts.ExecutionContext owner = owner(command);
        if (command.save() == null || !StringUtils.hasText(actorId)) {
            throw new IllegalArgumentException("project connection request is incomplete");
        }
        HttpApiConsoleContracts.ConnectionSaveRequest save = command.save();
        String origin = origin(save.origin());
        String mode = save.authMode() == null ? "" : save.authMode().toUpperCase(Locale.ROOT);
        if (!AUTH_MODES.contains(mode)) throw new Conflict("HTTP_API_AUTH_MISMATCH", "连接认证方式不在支持范围");
        RuntimeWorkflowCredentialRuntime credential = selectedCredential(mode, save.credentialRef(), owner);
        if (credential != null && !mode.equals(credential.type())) {
            throw new Conflict("HTTP_API_AUTH_MISMATCH", "项目凭据类型与连接认证方式不一致");
        }
        RuntimeHttpApiProjectConnectionEntity existing = projectConnections.selectByScopeForUpdate(
                owner.projectId(), owner.environment());
        if (existing != null && !Objects.equals(existing.getProjectCode(), owner.projectCode())) {
            throw new Conflict("HTTP_API_OWNER_CHANGED", "项目默认连接所属项目与当前 API 不一致，请刷新后核对");
        }
        Long expected = save.expectedRevision();
        if (existing == null ? expected != null : !Objects.equals(existing.getRevision(), expected)) {
            throw new Conflict("HTTP_API_PROJECT_CONNECTION_REVISION_CHANGED", "项目默认连接已变化，请刷新后比较并重新保存");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (existing == null) {
            existing = new RuntimeHttpApiProjectConnectionEntity();
            existing.setProjectId(owner.projectId());
            existing.setProjectCode(owner.projectCode());
            existing.setEnvironment(owner.environment());
            existing.setCreatedAt(now);
            existing.setRevision(1L);
        } else {
            existing.setRevision(existing.getRevision() + 1L);
        }
        existing.setOrigin(origin);
        existing.setAuthMode(mode);
        existing.setCredentialRef("NONE".equals(mode) ? null : save.credentialRef().trim());
        existing.setSavedBy(actorId.trim());
        existing.setUpdatedAt(now);
        try {
            if (existing.getId() == null) projectConnections.insert(existing); else projectConnections.updateById(existing);
        } catch (DuplicateKeyException duplicate) {
            throw new Conflict("HTTP_API_PROJECT_CONNECTION_REVISION_CHANGED", "项目默认连接已由其他操作保存，请刷新后重试");
        }
        return view(owner);
    }

    public RuntimeHttpApiConnectionEntity find(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) return null;
        return connections.selectOne(Wrappers.<RuntimeHttpApiConnectionEntity>lambdaQuery()
                .eq(RuntimeHttpApiConnectionEntity::getQualifiedName, qualifiedName).last("LIMIT 1"));
    }

    public RuntimeHttpApiProjectConnectionEntity findProjectDefault(Long projectId, String environment) {
        if (projectId == null || !StringUtils.hasText(environment)) return null;
        return projectConnections.selectOne(Wrappers.<RuntimeHttpApiProjectConnectionEntity>lambdaQuery()
                .eq(RuntimeHttpApiProjectConnectionEntity::getProjectId, projectId)
                .eq(RuntimeHttpApiProjectConnectionEntity::getEnvironment, environment).last("LIMIT 1"));
    }

    /**
     * Transient effective execution view (active override, else project default); callers must not persist
     * origin or credential reference in a release snapshot.
     */
    public ConnectionSnapshot snapshot(HttpApiConsoleContracts.ExecutionContext owner) {
        if (owner == null) return null;
        RuntimeHttpApiConnectionEntity override = find(owner.qualifiedName());
        if (override != null && active(override)) return fromOverride(override);
        if (override != null && !sameScope(owner, override)) return null;
        RuntimeHttpApiProjectConnectionEntity fallback = findProjectDefault(owner.projectId(), owner.environment());
        return fallback == null ? null : fromProjectDefault(fallback);
    }

    /**
     * Effective connection facts. {@code revision} is the opaque effective revision pinned by invocations;
     * {@code sourceRevision} is the revision of the row it came from.
     */
    public record ConnectionSnapshot(Long projectId, String projectCode, String environment,
                                     String origin, String authMode, String credentialRef, Long revision,
                                     String source, Long sourceRevision) {
        /** Override-shaped snapshot kept for existing fixtures. */
        public ConnectionSnapshot(Long projectId, String projectCode, String environment,
                                  String origin, String authMode, String credentialRef, Long revision) {
            this(projectId, projectCode, environment, origin, authMode, credentialRef, revision,
                    HttpApiConsoleContracts.ConnectionView.SOURCE_OVERRIDE, revision);
        }
        public boolean inherited() { return HttpApiConsoleContracts.ConnectionView.SOURCE_PROJECT_DEFAULT.equals(source); }
    }

    public static Long effectiveRevision(Long rowRevision, boolean override) {
        return rowRevision == null ? null : rowRevision * 2 + (override ? 1 : 0);
    }

    private static boolean active(RuntimeHttpApiConnectionEntity row) {
        return row != null && !Boolean.FALSE.equals(row.getOverrideActive());
    }

    private static ConnectionSnapshot fromOverride(RuntimeHttpApiConnectionEntity row) {
        return new ConnectionSnapshot(row.getProjectId(), row.getProjectCode(), row.getEnvironment(), row.getOrigin(),
                row.getAuthMode(), row.getCredentialRef(), effectiveRevision(row.getRevision(), true),
                HttpApiConsoleContracts.ConnectionView.SOURCE_OVERRIDE, row.getRevision());
    }

    private static ConnectionSnapshot fromProjectDefault(RuntimeHttpApiProjectConnectionEntity row) {
        return new ConnectionSnapshot(row.getProjectId(), row.getProjectCode(), row.getEnvironment(), row.getOrigin(),
                row.getAuthMode(), row.getCredentialRef(), effectiveRevision(row.getRevision(), false),
                HttpApiConsoleContracts.ConnectionView.SOURCE_PROJECT_DEFAULT, row.getRevision());
    }

    public HttpApiConsoleContracts.ExecutionContext owner(HttpApiConsoleContracts.ConnectionCommand command) {
        if (command == null || command.contractVersion() != HttpApiConsoleContracts.VERSION
                || command.apiId() == null || command.apiId() <= 0
                || !StringUtils.hasText(command.projectCode())) {
            throw new IllegalArgumentException("HTTP API owner identity is required");
        }
        HttpApiConsoleContracts.ExecutionContext owner = capability.getHttpApiExecutionContext(
                command.apiId(), command.projectCode());
        if (owner == null || !Objects.equals(owner.apiId(), command.apiId())
                || !Objects.equals(owner.qualifiedName(), command.qualifiedName())
                || !Objects.equals(owner.projectId(), command.projectId())
                || !Objects.equals(owner.projectCode(), command.projectCode())
                || !Objects.equals(owner.environment(), command.environment())) {
            throw new Conflict("HTTP_API_OWNER_CHANGED", "API 所属项目或环境已变化，请刷新后重试");
        }
        return owner;
    }

    public RuntimeWorkflowCredentialRuntime selectedCredential(String mode, String credentialRef,
                                                                HttpApiConsoleContracts.ExecutionContext owner) {
        if ("NONE".equals(mode)) {
            if (StringUtils.hasText(credentialRef)) throw new Conflict("HTTP_API_AUTH_MISMATCH", "无凭据模式不得绑定项目凭据");
            return null;
        }
        if (!StringUtils.hasText(credentialRef)) throw new Conflict("HTTP_API_CREDENTIAL_REQUIRED", "请选择当前项目的活动凭据");
        return credentials.resolveProjectCredential(credentialRef, owner.projectId(), owner.projectCode())
                .orElseThrow(() -> new Conflict("HTTP_API_CREDENTIAL_UNAVAILABLE", "项目凭据不存在、已停用或不属于当前项目"));
    }

    public String origin(String raw) {
        if (!StringUtils.hasText(raw) || !raw.equals(raw.trim()) || raw.contains("\\")) {
            throw new IllegalArgumentException("服务地址必须是完整的 HTTP(S) origin");
        }
        URI uri;
        try { uri = URI.create(raw); }
        catch (Exception invalid) { throw new IllegalArgumentException("服务地址格式无效", invalid); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("服务地址只支持 HTTP(S)");
        }
        if (!StringUtils.hasText(uri.getHost()) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (StringUtils.hasText(uri.getRawPath()) && !"/".equals(uri.getRawPath()))
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("服务地址只能包含协议、主机和端口");
        }
        String normalized;
        try { normalized = new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT),
                uri.getPort(), null, null, null).toString(); }
        catch (Exception invalid) { throw new IllegalArgumentException("服务地址格式无效", invalid); }
        egress.validateUri(normalized + "/");
        return normalized;
    }

    private HttpApiConsoleContracts.ConnectionView view(HttpApiConsoleContracts.ExecutionContext owner) {
        RuntimeHttpApiConnectionEntity override = find(owner.qualifiedName());
        RuntimeHttpApiProjectConnectionEntity fallback = findProjectDefault(owner.projectId(), owner.environment());
        HttpApiConsoleContracts.ProjectDefaultConnectionView projectDefault = projectDefaultView(owner, fallback);
        boolean overrideActive = active(override);
        Long overrideRevision = override == null ? null : override.getRevision();
        ConnectionSnapshot effective = overrideActive ? fromOverride(override)
                : override != null && !sameScope(owner, override) || fallback == null ? null : fromProjectDefault(fallback);
        boolean configurationAllowed = configurationAllowed(owner, override);
        if (effective == null) {
            String blocker = !owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus())
                    ? owner.sourceReason() == null ? "请先接纳当前来源契约" : owner.sourceReason()
                    : !owner.consoleCallSupported() ? owner.consoleUnsupportedReason() : null;
            if (blocker == null && override != null && !sameScope(owner, override)) {
                blocker = "连接所属项目或环境与当前 API 不一致";
            }
            return new HttpApiConsoleContracts.ConnectionView(owner.qualifiedName(),
                    owner.projectId(), owner.projectCode(), owner.environment(), null, null, null, null, null,
                    null, blocker == null ? "UNCONFIGURED" : "BLOCKED",
                    blocker == null ? "尚未配置项目默认连接或本 API 覆盖连接" : blocker, null,
                    verification(owner, null, null, blocker), configurationAllowed,
                    null, false, false, overrideRevision, projectDefault);
        }
        String reason = null;
        if (!Objects.equals(effective.projectId(), owner.projectId())
                || !Objects.equals(effective.projectCode(), owner.projectCode())
                || !Objects.equals(effective.environment(), owner.environment())) {
            reason = "连接所属项目或环境与当前 API 不一致";
        }
        RuntimeWorkflowCredentialRuntime credential = null;
        if (reason == null) {
            try {
                origin(effective.origin());
                credential = selectedCredential(effective.authMode(), effective.credentialRef(), owner);
                reason = HttpApiConsolePolicy.connectionAuthReason(owner.acceptedContract(), effective.authMode(),
                        credential == null ? null : credential.type(), credentialHeader(credential));
                if (reason != null && effective.inherited()) {
                    reason = "项目默认连接不适用于当前 API：" + reason + "；可为本 API 单独配置覆盖连接";
                }
            } catch (RuntimeException invalid) {
                reason = invalid instanceof Conflict ? invalid.getMessage() : "服务地址当前不可用，请检查连接配置";
            }
        }
        if (reason == null && (!owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus())
                || !Objects.equals(owner.acceptedContractHash(), owner.candidateContractHash()))) {
            reason = owner.sourceReason() == null ? "当前 API 契约尚未接纳" : owner.sourceReason();
        }
        if (reason == null && !owner.consoleCallSupported()) {
            reason = owner.consoleUnsupportedReason() == null ? "当前 API 契约尚不支持试调用" : owner.consoleUnsupportedReason();
        }
        return new HttpApiConsoleContracts.ConnectionView(owner.qualifiedName(), owner.projectId(),
                owner.projectCode(), owner.environment(), effective.origin(), effective.authMode(),
                effective.credentialRef(), credential == null ? null : credential.name(),
                credential == null ? null : credential.revision(), effective.revision(),
                reason == null ? "CONFIGURED" : "BLOCKED", reason,
                effective.origin() + owner.routeTemplate(),
                verification(owner, effective, credential == null ? null : credential.revision(), reason), configurationAllowed,
                effective.source(), effective.inherited(), overrideActive, overrideRevision, projectDefault);
    }

    private HttpApiConsoleContracts.ProjectDefaultConnectionView projectDefaultView(
            HttpApiConsoleContracts.ExecutionContext owner, RuntimeHttpApiProjectConnectionEntity saved) {
        if (saved == null) {
            return new HttpApiConsoleContracts.ProjectDefaultConnectionView(owner.projectId(), owner.projectCode(),
                    owner.environment(), null, null, null, null, null, "UNCONFIGURED",
                    "尚未保存本项目默认连接", true);
        }
        boolean sameProject = Objects.equals(saved.getProjectCode(), owner.projectCode());
        String reason = sameProject ? null : "项目默认连接所属项目与当前 API 不一致";
        RuntimeWorkflowCredentialRuntime credential = null;
        if (reason == null) {
            try {
                origin(saved.getOrigin());
                credential = selectedCredential(saved.getAuthMode(), saved.getCredentialRef(), owner);
                if (credential != null && !Objects.equals(saved.getAuthMode(), credential.type())) {
                    reason = "项目凭据类型与连接认证方式不一致";
                }
            } catch (RuntimeException invalid) {
                reason = invalid instanceof Conflict ? invalid.getMessage() : "服务地址当前不可用，请检查连接配置";
            }
        }
        return new HttpApiConsoleContracts.ProjectDefaultConnectionView(saved.getProjectId(), saved.getProjectCode(),
                saved.getEnvironment(), saved.getOrigin(), saved.getAuthMode(), saved.getCredentialRef(),
                credential == null ? null : credential.name(), saved.getRevision(),
                reason == null ? "CONFIGURED" : "BLOCKED", reason, sameProject);
    }

    /** A broken origin/credential can be replaced; source, contract and ownership are never repaired by a save. */
    private boolean configurationAllowed(HttpApiConsoleContracts.ExecutionContext owner, RuntimeHttpApiConnectionEntity saved) {
        return owner.sourceConfirmed() && "ACCEPTED".equals(owner.sourceStatus()) && owner.consoleCallSupported()
                && StringUtils.hasText(owner.acceptedContractHash())
                && Objects.equals(owner.acceptedContractHash(), owner.candidateContractHash()) && sameScope(owner, saved);
    }

    private boolean sameScope(HttpApiConsoleContracts.ExecutionContext owner, RuntimeHttpApiConnectionEntity saved) {
        return saved == null || Objects.equals(saved.getProjectId(), owner.projectId())
                && Objects.equals(saved.getProjectCode(), owner.projectCode())
                && Objects.equals(saved.getEnvironment(), owner.environment());
    }

    private HttpApiConsoleContracts.MarketVerificationView verification(HttpApiConsoleContracts.ExecutionContext owner,
            ConnectionSnapshot effective, String credentialRevision, String reason) {
        if (!RuntimeHttpApiVerificationService.isMarket(owner.qualifiedName())) return null;
        if (verification == null) return RuntimeHttpApiVerificationService.unavailable("UNKNOWN", "当前验证事实不可读取，请稍后刷新");
        return verification.read(owner, effective == null ? null : effective.revision(), credentialRevision, reason);
    }

    private String credentialHeader(RuntimeWorkflowCredentialRuntime credential) {
        if (credential == null || credential.secret() == null) return null;
        Object name = credential.secret().get("headerName");
        return name instanceof String text ? text : null;
    }

    public static class Conflict extends RuntimeException {
        private final String code;
        public Conflict(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
