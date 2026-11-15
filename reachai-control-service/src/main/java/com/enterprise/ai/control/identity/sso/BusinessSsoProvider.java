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
 * @param defaultRole    role given to an employee who signs in here and has no role in this system yet, so one
 *                       call-permission rule can open the system to everyone who can log in to it
 * @param employeeToken  whether calls made for an employee who signed in here carry the employee's own
 *                       business-system token instead of the system's shared credential: OFF, PREFERRED (own
 *                       token while it works, otherwise the shared credential) or REQUIRED (never the shared one)
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
                                  boolean active,
                                  String mode,
                                  PasswordLogin passwordLogin,
                                  String defaultRole,
                                  String employeeToken) {

    public static final String TYPE = "BUSINESS_OAUTH";
    /** The business system has an OAuth2 / OIDC server: the employee signs in on the system's own page. */
    public static final String MODE_OAUTH = "OAUTH";
    /**
     * The business system only has an ordinary login API: the employee types their account on ReachAI's login
     * page and ReachAI asks the system's login API whether it is right. Nothing is installed in the system.
     */
    public static final String MODE_PASSWORD = "PASSWORD";

    public static final String EMPLOYEE_TOKEN_OFF = "OFF";
    public static final String EMPLOYEE_TOKEN_PREFERRED = "PREFERRED";
    public static final String EMPLOYEE_TOKEN_REQUIRED = "REQUIRED";

    public BusinessSsoProvider {
        employeeToken = EMPLOYEE_TOKEN_REQUIRED.equalsIgnoreCase(employeeToken) ? EMPLOYEE_TOKEN_REQUIRED
                : EMPLOYEE_TOKEN_PREFERRED.equalsIgnoreCase(employeeToken) ? EMPLOYEE_TOKEN_PREFERRED
                : EMPLOYEE_TOKEN_OFF;
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        mode = MODE_PASSWORD.equals(mode) ? MODE_PASSWORD : MODE_OAUTH;
        passwordLogin = passwordLogin == null ? PasswordLogin.EMPTY : passwordLogin;
    }

    public BusinessSsoProvider(String code, String label, String projectCode, String authorizeUrl, String tokenUrl,
                               String userInfoUrl, String clientId, String clientSecretCipher, String scope,
                               String userIdPath, String userNamePath, Map<String, String> extraHeaders,
                               boolean active) {
        this(code, label, projectCode, authorizeUrl, tokenUrl, userInfoUrl, clientId, clientSecretCipher, scope,
                userIdPath, userNamePath, extraHeaders, active, MODE_OAUTH, null, null);
    }

    public BusinessSsoProvider(String code, String label, String projectCode, String authorizeUrl, String tokenUrl,
                               String userInfoUrl, String clientId, String clientSecretCipher, String scope,
                               String userIdPath, String userNamePath, Map<String, String> extraHeaders,
                               boolean active, String mode, PasswordLogin passwordLogin, String defaultRole) {
        this(code, label, projectCode, authorizeUrl, tokenUrl, userInfoUrl, clientId, clientSecretCipher, scope,
                userIdPath, userNamePath, extraHeaders, active, mode, passwordLogin, defaultRole, EMPLOYEE_TOKEN_OFF);
    }

    public boolean usesEmployeeToken() {
        return !EMPLOYEE_TOKEN_OFF.equals(employeeToken);
    }

    public boolean clientSecretPresent() {
        return clientSecretCipher != null && !clientSecretCipher.isBlank();
    }

    public boolean passwordMode() {
        return MODE_PASSWORD.equals(mode);
    }

    /**
     * How to ask the system's login API (the {@code tokenUrl}) about an account.
     *
     * @param contentType JSON or FORM
     * @param body        request template; {@code {{username}}}, {@code {{password}}} and {@code {{password_md5}}}
     *                    are replaced with what the employee typed
     * @param tokenPath   dotted path to the login token in the answer; only needed with a user-info address
     * @param tokenHeader header that carries the token to the user-info address (default Authorization)
     * @param tokenPrefix put before the token (default {@code Bearer } for Authorization, otherwise nothing)
     */
    public record PasswordLogin(String contentType, String body, String tokenPath, String tokenHeader,
                                String tokenPrefix) {
        static final PasswordLogin EMPTY = new PasswordLogin(null, null, null, null, null);

        public PasswordLogin {
            contentType = "FORM".equalsIgnoreCase(contentType) ? "FORM" : "JSON";
        }
    }
}
