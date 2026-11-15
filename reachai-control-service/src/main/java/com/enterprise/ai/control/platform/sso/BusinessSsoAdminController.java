package com.enterprise.ai.control.platform.sso;

import com.enterprise.ai.control.identity.sso.BusinessSsoProvider;
import com.enterprise.ai.control.identity.sso.BusinessSsoProviderStore;
import com.enterprise.ai.control.identity.sso.BusinessSsoSecretCipher;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.enterprise.ai.control.platform.PlatformPublicOrigin;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 平台管理 → 认证源: configure 「用业务系统账号登录」 entries. The client secret is encrypted before it is stored and
 * is never returned; leaving it empty on save keeps the stored one.
 */
@RestController
@RequiredArgsConstructor
public class BusinessSsoAdminController {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,27}");
    private static final Pattern ROLE = Pattern.compile("[A-Za-z0-9_:-]{1,64}");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Set<String> RESERVED_HEADERS = Set.of("authorization", "host", "cookie", "content-type",
            "content-length", "accept", "connection", "transfer-encoding");

    private final BusinessSsoProviderStore store;
    private final BusinessSsoSecretCipher cipher;
    private final PlatformRequestAuthorization requestAuthorization;
    private final PlatformAuthAuditService audit;
    private final McpHubProperties properties;

    @GetMapping("/api/platform/sso-providers")
    public ResponseEntity<SsoProviderList> list(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(new SsoProviderList(callbackUrl(request), cipher.available(),
                store.list().stream().map(BusinessSsoAdminController::view).toList()));
    }

    @PutMapping("/api/platform/sso-providers/{code}")
    @Transactional
    public ResponseEntity<SsoProviderView> save(HttpServletRequest request, @PathVariable String code,
                                                @RequestBody SsoProviderCommand command) {
        PlatformAuthenticatedSession actor = requireAdmin(request);
        if (command == null) throw badRequest("请填写登录配置");
        String normalizedCode = code == null ? "" : code.trim();
        if (!CODE.matcher(normalizedCode).matches()) {
            throw badRequest("编码只能用小写字母、数字和横线，2 到 28 位，以字母开头");
        }
        BusinessSsoProvider existing = store.find(normalizedCode).orElse(null);
        String secretCipher = existing == null ? null : existing.clientSecretCipher();
        if (StringUtils.hasText(command.clientSecret())) {
            if (!cipher.available()) throw conflict("服务器没有配置加密密钥，暂时不能保存登录密钥");
            secretCipher = cipher.encrypt(command.clientSecret().trim());
        }
        boolean active = Boolean.TRUE.equals(command.active());
        boolean passwordMode = BusinessSsoProvider.MODE_PASSWORD.equalsIgnoreCase(command.mode());
        if (active && !passwordMode && !StringUtils.hasText(secretCipher)) throw badRequest("启用前请填写 Client Secret");
        BusinessSsoProvider saved;
        try {
            saved = store.save(passwordMode ? passwordProvider(normalizedCode, command, active)
                    : new BusinessSsoProvider(normalizedCode,
                    required(command.label(), "按钮名称", 64),
                    required(command.projectCode(), "业务系统", 64),
                    url(command.authorizeUrl(), "登录页地址"),
                    url(command.tokenUrl(), "换令牌地址"),
                    url(command.userInfoUrl(), "用户信息地址"),
                    required(command.clientId(), "Client ID", 128),
                    secretCipher,
                    optional(command.scope(), 256),
                    required(command.userIdPath(), "用户 ID 字段", 64),
                    optional(command.userNamePath(), 64),
                    headers(command.extraHeaders()),
                    active, BusinessSsoProvider.MODE_OAUTH, null, role(command.defaultRole()),
                    command.employeeToken()));
        } catch (IllegalArgumentException conflict) {
            throw conflict(conflict.getMessage());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("providerCode", saved.code());
        details.put("providerType", BusinessSsoProvider.TYPE);
        details.put("mode", saved.mode());
        details.put("projectCode", saved.projectCode());
        details.put("employeeToken", saved.employeeToken());
        details.put("status", saved.active() ? "ACTIVE" : "INACTIVE");
        details.put("clientSecretChanged", StringUtils.hasText(command.clientSecret()));
        audit.record(actor, "PLATFORM_AUTH_PROVIDER_SAVED", "AUTH_PROVIDER", saved.code(), details);
        return ResponseEntity.ok(view(saved));
    }

    /** 账号密码验证: only the system's login API is needed; nothing is registered in the business system. */
    private static BusinessSsoProvider passwordProvider(String code, SsoProviderCommand command, boolean active) {
        String body = required(command.loginBody(), "登录请求内容", 2000);
        if (!body.contains("{{username}}") || !(body.contains("{{password}}") || body.contains("{{password_md5}}"))) {
            throw badRequest("登录请求内容里要有 {{username}} 和 {{password}}（或 {{password_md5}}）");
        }
        String userInfoUrl = StringUtils.hasText(command.userInfoUrl()) ? url(command.userInfoUrl(), "用户信息地址") : null;
        // Required: a token in the answer is the only proof the business system accepted the password.
        String tokenPath = required(command.tokenPath(), "登录令牌字段", 64);
        String tokenHeader = optional(command.tokenHeader(), 64);
        if (tokenHeader != null && !HEADER_NAME.matcher(tokenHeader).matches()) throw badRequest("令牌请求头不合法");
        String prefix = command.tokenPrefix();
        if (prefix != null && (prefix.length() > 32 || prefix.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f))) {
            throw badRequest("令牌前缀不合法");
        }
        return new BusinessSsoProvider(code,
                required(command.label(), "按钮名称", 64),
                required(command.projectCode(), "业务系统", 64),
                null, url(command.tokenUrl(), "登录接口地址"), userInfoUrl, null, null, null,
                optional(command.userIdPath(), 64), optional(command.userNamePath(), 64),
                headers(command.extraHeaders()), active, BusinessSsoProvider.MODE_PASSWORD,
                new BusinessSsoProvider.PasswordLogin(command.loginContentType(), body, tokenPath, tokenHeader, prefix),
                role(command.defaultRole()), command.employeeToken());
    }

    private PlatformAuthenticatedSession requireAdmin(HttpServletRequest request) {
        return requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_ADMIN);
    }

    private String callbackUrl(HttpServletRequest request) {
        return PlatformPublicOrigin.resolve(properties, request) + BusinessSsoService.CALLBACK_PATH;
    }

    private static SsoProviderView view(BusinessSsoProvider provider) {
        return new SsoProviderView(provider.code(), provider.label(), provider.projectCode(), provider.authorizeUrl(),
                provider.tokenUrl(), provider.userInfoUrl(), provider.clientId(), provider.clientSecretPresent(),
                provider.scope(), provider.userIdPath(), provider.userNamePath(), provider.extraHeaders(),
                provider.active(), provider.mode(), provider.passwordLogin().contentType(),
                provider.passwordLogin().body(), provider.passwordLogin().tokenPath(),
                provider.passwordLogin().tokenHeader(), provider.passwordLogin().tokenPrefix(), provider.defaultRole(),
                provider.employeeToken());
    }

    private static String role(String value) {
        String text = optional(value, 64);
        if (text != null && !ROLE.matcher(text).matches()) throw badRequest("默认角色只能用字母、数字、下划线、横线和冒号");
        return text;
    }

    private static String required(String value, String label, int max) {
        if (!StringUtils.hasText(value)) throw badRequest("请填写" + label);
        String text = value.trim();
        if (text.length() > max) throw badRequest(label + "太长了");
        return text;
    }

    private static String optional(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String text = value.trim();
        if (text.length() > max) throw badRequest("内容太长了：" + text.substring(0, 16) + "…");
        return text;
    }

    static String url(String value, String label) {
        String text = required(value, label, 512);
        try {
            URI uri = URI.create(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw badRequest(label + "要以 http:// 或 https:// 开头，且不能带账号密码");
            }
        } catch (IllegalArgumentException malformed) {
            throw badRequest(label + "格式不对");
        }
        return text;
    }

    static Map<String, String> headers(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        if (raw.size() > 5) throw badRequest("附加请求头最多 5 个");
        Map<String, String> headers = new LinkedHashMap<>();
        raw.forEach((name, value) -> {
            String key = name == null ? "" : name.trim();
            if (!HEADER_NAME.matcher(key).matches() || RESERVED_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
                throw badRequest("不能使用这个请求头：" + key);
            }
            String text = value == null ? "" : value.trim();
            if (text.length() > 256 || text.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
                throw badRequest("请求头的值不合法：" + key);
            }
            headers.put(key, text);
        });
        return headers;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record SsoProviderList(String callbackUrl, boolean encryptionAvailable, List<SsoProviderView> providers) {
    }

    public record SsoProviderView(String code, String label, String projectCode, String authorizeUrl,
                                  String tokenUrl, String userInfoUrl, String clientId, boolean clientSecretPresent,
                                  String scope, String userIdPath, String userNamePath,
                                  Map<String, String> extraHeaders, boolean active, String mode,
                                  String loginContentType, String loginBody, String tokenPath,
                                  String tokenHeader, String tokenPrefix, String defaultRole,
                                  String employeeToken) {
    }

    public record SsoProviderCommand(String label, String projectCode, String authorizeUrl, String tokenUrl,
                                     String userInfoUrl, String clientId, String clientSecret, String scope,
                                     String userIdPath, String userNamePath, Map<String, String> extraHeaders,
                                     Boolean active, String mode, String loginContentType, String loginBody,
                                     String tokenPath, String tokenHeader, String tokenPrefix,
                                     String defaultRole, String employeeToken) {
    }
}
