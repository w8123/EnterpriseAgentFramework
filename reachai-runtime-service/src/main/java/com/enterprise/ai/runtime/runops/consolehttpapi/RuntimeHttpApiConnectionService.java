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

/** Revisioned API origin/auth binding, independently checked against the Capability owner. */
@Service
@RequiredArgsConstructor
public class RuntimeHttpApiConnectionService {
    private final RuntimeHttpApiConnectionMapper connections;
    private final RuntimeCapabilityCatalogClient capability;
    private final RuntimeWorkflowCredentialService credentials;
    private final WorkflowHttpEgressPolicy egress;
    private RuntimeHttpApiVerificationService verification;

    @Autowired
    public void setVerification(RuntimeHttpApiVerificationService verification) { this.verification = verification; }

    public HttpApiConsoleContracts.ConnectionView read(HttpApiConsoleContracts.ConnectionCommand command) {
        HttpApiConsoleContracts.ExecutionContext owner = owner(command);
        RuntimeHttpApiConnectionEntity saved = find(owner.qualifiedName());
        return view(owner, saved);
    }

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
        existing.setSavedBy(actorId.trim());
        existing.setUpdatedAt(now);
        try {
            if (existing.getId() == null) connections.insert(existing); else connections.updateById(existing);
        } catch (DuplicateKeyException duplicate) {
            throw new Conflict("HTTP_API_CONNECTION_REVISION_CHANGED", "连接配置已由其他操作保存，请刷新后重试");
        }
        return view(owner, existing);
    }

    public RuntimeHttpApiConnectionEntity find(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) return null;
        return connections.selectOne(Wrappers.<RuntimeHttpApiConnectionEntity>lambdaQuery()
                .eq(RuntimeHttpApiConnectionEntity::getQualifiedName, qualifiedName).last("LIMIT 1"));
    }

    /** Transient execution view; callers must not persist origin or credential reference in a release snapshot. */
    public ConnectionSnapshot snapshot(String qualifiedName) {
        RuntimeHttpApiConnectionEntity saved = find(qualifiedName);
        return saved == null ? null : new ConnectionSnapshot(saved.getProjectId(), saved.getProjectCode(),
                saved.getEnvironment(), saved.getOrigin(), saved.getAuthMode(),
                saved.getCredentialRef(), saved.getRevision());
    }

    public record ConnectionSnapshot(Long projectId, String projectCode, String environment,
                                     String origin, String authMode, String credentialRef, Long revision) {
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

    private HttpApiConsoleContracts.ConnectionView view(HttpApiConsoleContracts.ExecutionContext owner,
                                                        RuntimeHttpApiConnectionEntity saved) {
        if (saved == null) {
            String blocker = !owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus())
                    ? owner.sourceReason() == null ? "请先接纳当前来源契约" : owner.sourceReason()
                    : !owner.consoleCallSupported() ? owner.consoleUnsupportedReason() : null;
            return new HttpApiConsoleContracts.ConnectionView(owner.qualifiedName(),
                    owner.projectId(), owner.projectCode(), owner.environment(), null, null, null, null, null,
                    null, blocker == null ? "UNCONFIGURED" : "BLOCKED",
                    blocker == null ? "尚未保存服务地址与认证方式" : blocker, null,
                    verification(owner, null, null, blocker));
        }
        String reason = null;
        if (!Objects.equals(saved.getProjectId(), owner.projectId())
                || !Objects.equals(saved.getProjectCode(), owner.projectCode())
                || !Objects.equals(saved.getEnvironment(), owner.environment())) {
            reason = "连接所属项目或环境与当前 API 不一致";
        }
        RuntimeWorkflowCredentialRuntime credential = null;
        if (reason == null) {
            try {
                origin(saved.getOrigin());
                credential = selectedCredential(saved.getAuthMode(), saved.getCredentialRef(), owner);
                reason = HttpApiConsolePolicy.connectionAuthReason(owner.acceptedContract(), saved.getAuthMode(),
                        credential == null ? null : credential.type(), credentialHeader(credential));
            } catch (RuntimeException invalid) {
                reason = invalid instanceof Conflict ? invalid.getMessage() : "服务地址当前不可用，请检查连接配置";
            }
        }
        if (reason == null && (!owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus())
                || !Objects.equals(owner.acceptedContractHash(), owner.candidateContractHash()))) {
            reason = owner.sourceReason() == null ? "当前 API 契约尚未接纳" : owner.sourceReason();
        }
        return new HttpApiConsoleContracts.ConnectionView(owner.qualifiedName(), owner.projectId(),
                owner.projectCode(), owner.environment(), saved.getOrigin(), saved.getAuthMode(),
                saved.getCredentialRef(), credential == null ? null : credential.name(),
                credential == null ? null : credential.revision(), saved.getRevision(),
                reason == null ? "CONFIGURED" : "BLOCKED", reason,
                saved.getOrigin() + owner.routeTemplate(),
                verification(owner, saved, credential == null ? null : credential.revision(), reason));
    }

    private HttpApiConsoleContracts.MarketVerificationView verification(HttpApiConsoleContracts.ExecutionContext owner,
            RuntimeHttpApiConnectionEntity saved, String credentialRevision, String reason) {
        if (!RuntimeHttpApiVerificationService.isMarket(owner.qualifiedName())) return null;
        if (verification == null) return RuntimeHttpApiVerificationService.unavailable("UNKNOWN", "当前验证事实不可读取，请稍后刷新");
        return verification.read(owner, saved, credentialRevision, reason);
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
