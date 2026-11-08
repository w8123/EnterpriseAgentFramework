package com.enterprise.ai.control.identity.sso;

import java.util.Map;

/**
 * One 「用业务系统账号登录」 entry, stored in control_platform_auth_provider (type BUSINESS_OAUTH).
 * Standard OAuth2 authorization code flow; works with 芋道's built-in OAuth2 server and OIDC providers.
 *
 * @param code           provider code, also the platform-user {@code source_provider} for bound console accounts
 * @param projectCode    the ReachAI business system the accounts belong to (business user directory app id)
 * @param userIdPath     dotted path to the account id in the user-info response, e.g. {@code data.id} or {@code sub}
 * @param userNamePath   dotted path to the display name
 * @param extraHeaders   sent with the token and user-info calls (芋道 needs {@code tenant-id})
 */
public record BusinessSsoProvider(String code,
                                  String label,
                                  String projectCode,
                                  String authorizeUrl,
                                  String tokenUrl,
                                  String userInfoUrl,
                                  String clientId,
                                  String clientSecretCipher,
                                  String scope,
                                  String userIdPath,
                                  String userNamePath,
                                  Map<String, String> extraHeaders,
                                  boolean active) {

    public static final String TYPE = "BUSINESS_OAUTH";

    public BusinessSsoProvider {
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }

    public boolean clientSecretPresent() {
        return clientSecretCipher != null && !clientSecretCipher.isBlank();
    }
}
