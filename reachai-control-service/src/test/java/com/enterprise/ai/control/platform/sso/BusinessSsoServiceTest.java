package com.enterprise.ai.control.platform.sso;

import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.AttestedBusinessUser;
import com.enterprise.ai.control.identity.BusinessUserSyncService.SyncedBusinessUser;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformExternalLoginService;
import com.enterprise.ai.control.identity.sso.BusinessSsoProvider;
import com.enterprise.ai.control.identity.sso.BusinessSsoProviderStore;
import com.enterprise.ai.control.identity.sso.BusinessSsoSecretCipher;
import com.enterprise.ai.control.platform.EmployeePortalSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessSsoServiceTest {

    private final BusinessSsoProviderStore store = mock(BusinessSsoProviderStore.class);
    private final BusinessSsoSecretCipher cipher = new BusinessSsoSecretCipher("", "test-internal-secret");
    private final BusinessSsoHttp http = mock(BusinessSsoHttp.class);
    private final BusinessUserSyncService userSync = mock(BusinessUserSyncService.class);
    private final EmployeePortalSessionService portal = new EmployeePortalSessionService();
    private final PlatformExternalLoginService consoleLogins = mock(PlatformExternalLoginService.class);
    private final PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
    private final EmployeeBusinessTokens employeeTokens = new EmployeeBusinessTokens(store);
    private BusinessSsoService service;

    @BeforeEach
    void setUp() {
        service = new BusinessSsoService(store, cipher, http, userSync, portal, consoleLogins, audit, employeeTokens);
        when(consoleLogins.signInBoundAccount(anyString(), anyString(), any(), anyMap())).thenReturn(Optional.empty());
        BusinessSsoProvider yudao = new BusinessSsoProvider("yudao", "芋道 ERP", "yudao-erp",
                "https://erp.example.com/sso", "https://erp.example.com/admin-api/system/oauth2/token",
                "https://erp.example.com/admin-api/system/oauth2/user/get", "reachai-login",
                cipher.encrypt("s3cret"), "user.read", "data.id", "data.nickname", Map.of("tenant-id", "1"), true);
        when(store.find("yudao")).thenReturn(Optional.of(yudao));
        when(store.list()).thenReturn(List.of(yudao));
        when(http.postForm(anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of("code", 0, "data", Map.of("access_token", "at-1")));
        when(http.getJson(anyString(), anyMap()))
                .thenReturn(Map.of("code", 0, "data", Map.of("id", 7, "nickname", "张三")));
    }

    @Test
    void startSendsTheBrowserToTheBusinessLoginWithASingleUseState() {
        String url = service.start("yudao", "https://reachai.example.com");

        assertThat(url).startsWith("https://erp.example.com/sso?response_type=code&client_id=reachai-login")
                .contains("redirect_uri=https%3A%2F%2Freachai.example.com%2Fapi%2Fplatform%2Fauth%2Fsso%2Fcallback")
                .contains("scope=user.read");
        assertThat(service.buttons()).extracting(BusinessSsoService.LoginButton::label).containsExactly("芋道 ERP");
    }

    @Test
    void employeeLandsOnThePersonalPageWithRolesKept() {
        when(userSync.syncKeepingRoles(any())).thenReturn(new SyncedBusinessUser(11L, "default", "local:x", "张三",
                "yudao-erp", "7", "张三", List.of("sales")));
        String state = state(service.start("yudao", "https://reachai.example.com"));

        BusinessSsoService.Outcome outcome = service.callback("code-1", state, null, new MockHttpServletRequest());

        assertThat(outcome.location()).startsWith("/my/ai-tools#ticket=");
        assertThat(outcome.cookies()).isEmpty();
        ArgumentCaptor<AttestedBusinessUser> attested = ArgumentCaptor.forClass(AttestedBusinessUser.class);
        verify(userSync).syncKeepingRoles(attested.capture());
        assertThat(attested.getValue().appId()).isEqualTo("yudao-erp");
        assertThat(attested.getValue().externalUserId()).isEqualTo("7");
        String ticket = outcome.location().substring("/my/ai-tools#ticket=".length());
        assertThat(portal.redeem(ticket)).get().extracting(issued -> issued.owner().roles())
                .isEqualTo(List.of("sales"));
        ArgumentCaptor<Map<String, String>> tokenHeaders = ArgumentCaptor.forClass(Map.class);
        verify(http).postForm(eq("https://erp.example.com/admin-api/system/oauth2/token"), anyMap(),
                tokenHeaders.capture());
        assertThat(tokenHeaders.getValue()).containsEntry("tenant-id", "1")
                .containsEntry("Authorization", "Basic cmVhY2hhaS1sb2dpbjpzM2NyZXQ=");
        verify(audit).record(isNull(), eq("EMPLOYEE_SSO_LOGIN_SUCCEEDED"), eq("BUSINESS_USER"), eq("11"), anyMap());
    }

    @Test
    void boundPlatformAccountGetsAConsoleSession() {
        when(consoleLogins.signInBoundAccount(eq("yudao"), eq("7"), any(), anyMap())).thenReturn(Optional.of(
                new PlatformExternalLoginService.ConsoleLogin(3L, List.of("reachai_platform_session=x"))));
        String state = state(service.start("yudao", "https://reachai.example.com"));

        BusinessSsoService.Outcome outcome = service.callback("code-1", state, null, new MockHttpServletRequest());

        assertThat(outcome.location()).isEqualTo("/");
        assertThat(outcome.cookies()).contains("reachai_platform_session=x");
        verify(userSync, never()).syncKeepingRoles(any());
    }

    @Test
    void disabledBoundAccountIsRefused() {
        when(consoleLogins.signInBoundAccount(eq("yudao"), eq("7"), any(), anyMap()))
                .thenThrow(new PlatformExternalLoginService.BoundAccountDisabledException());
        String state = state(service.start("yudao", "https://reachai.example.com"));

        BusinessSsoService.Outcome outcome = service.callback("code-1", state, null, new MockHttpServletRequest());

        assertThat(outcome.location()).startsWith("/login?sso_error=");
        verify(userSync, never()).syncKeepingRoles(any());
    }

    @Test
    void stateCanBeUsedOnlyOnce() {
        when(userSync.syncKeepingRoles(any())).thenReturn(new SyncedBusinessUser(11L, "default", "local:x", "张三",
                "yudao-erp", "7", "张三", List.of()));
        String state = state(service.start("yudao", "https://reachai.example.com"));
        service.callback("code-1", state, null, new MockHttpServletRequest());

        BusinessSsoService.Outcome replay = service.callback("code-1", state, null, new MockHttpServletRequest());

        assertThat(replay.location()).startsWith("/login?sso_error=");
        verify(audit).record(isNull(), eq("BUSINESS_SSO_LOGIN_FAILED"), eq("AUTH_PROVIDER"), isNull(), anyMap());
    }

    @Test
    void businessSystemRefusalIsShownOnTheLoginPage() {
        when(http.getJson(anyString(), anyMap())).thenReturn(Map.of("code", 401, "msg", "账号未登录"));
        String state = state(service.start("yudao", "https://reachai.example.com"));

        BusinessSsoService.Outcome outcome = service.callback("code-1", state, null, new MockHttpServletRequest());

        assertThat(java.net.URLDecoder.decode(outcome.location(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("账号未登录");
        verify(userSync, never()).syncKeepingRoles(any());
    }

    @Test
    void secretsRoundTripAndAreNotStoredInClearText() {
        String sealed = cipher.encrypt("s3cret");
        assertThat(sealed).startsWith(BusinessSsoSecretCipher.PREFIX).doesNotContain("s3cret");
        assertThat(cipher.decrypt(sealed)).isEqualTo("s3cret");
        assertThat(new BusinessSsoSecretCipher("", "other-secret").available()).isTrue();
    }

    private static String state(String url) {
        return java.net.URI.create(url).getQuery().replaceAll(".*state=([0-9a-f]+).*", "$1");
    }

    private void passwordProvider(String userInfoUrl, String userIdPath, String defaultRole) {
        passwordProvider(userInfoUrl, userIdPath, defaultRole, BusinessSsoProvider.EMPLOYEE_TOKEN_OFF);
    }

    private void passwordProvider(String userInfoUrl, String userIdPath, String defaultRole, String employeeToken) {
        BusinessSsoProvider mall = new BusinessSsoProvider("mall", "商城后台", "mall",
                null, "https://mall.example.com/login", userInfoUrl, null, null, null, userIdPath, "data.nickName",
                Map.of(), true, BusinessSsoProvider.MODE_PASSWORD,
                new BusinessSsoProvider.PasswordLogin("JSON",
                        "{\"userName\":\"{{username}}\",\"passwordMd5\":\"{{password_md5}}\"}", "data", "token", ""),
                defaultRole, employeeToken);
        when(store.find("mall")).thenReturn(Optional.of(mall));
        when(store.list()).thenReturn(List.of(mall));
    }

    @Test
    void theEmployeesOwnTokenIsKeptOnlyWhenTheEntryAsksForIt() {
        when(http.postBody(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("resultCode", 200, "data", "tok-own"));
        when(userSync.syncKeepingRoles(any())).thenReturn(new SyncedBusinessUser(22L, "default", "local:n", "admin",
                "mall", "admin", "admin", List.of("ops")));

        passwordProvider(null, null, null);
        assertThat(service.passwordLogin("mall", "admin", "pw", new MockHttpServletRequest()).error()).isNull();
        assertThat(employeeTokens.resolve("default", "mall", "admin").mode()).isEqualTo("OFF");
        assertThat(employeeTokens.resolve("default", "mall", "admin").token()).isNull();

        passwordProvider(null, null, null, BusinessSsoProvider.EMPLOYEE_TOKEN_REQUIRED);
        // Signed in before the switch was turned on: there is no token, and none is invented.
        assertThat(employeeTokens.resolve("default", "mall", "admin").token()).isNull();
        assertThat(service.passwordLogin("mall", "admin", "pw", new MockHttpServletRequest()).error()).isNull();
        EmployeeBusinessTokens.Resolution own = employeeTokens.resolve("default", "mall", "admin");
        assertThat(own.mode()).isEqualTo("REQUIRED");
        assertThat(own.token()).isEqualTo("tok-own");
        assertThat(own.origins()).containsExactly("https://mall.example.com:443");
        assertThat(own.toString()).doesNotContain("tok-own");
        // Another account, another system and the platform's evaluation account never get this token.
        assertThat(employeeTokens.resolve("default", "mall", "someone-else").token()).isNull();
        assertThat(employeeTokens.resolve("default", "other", "admin").mode()).isEqualTo("OFF");
        assertThat(employeeTokens.resolve("default", "mall", "reachai-eval").mode()).isEqualTo("OFF");

        employeeTokens.expired("default", "mall", "admin");
        assertThat(employeeTokens.resolve("default", "mall", "admin").token()).isNull();

        // Turning the switch off forgets what was kept.
        assertThat(service.passwordLogin("mall", "admin", "pw", new MockHttpServletRequest()).error()).isNull();
        passwordProvider(null, null, null);
        assertThat(employeeTokens.resolve("default", "mall", "admin").token()).isNull();
        passwordProvider(null, null, null, BusinessSsoProvider.EMPLOYEE_TOKEN_PREFERRED);
        assertThat(employeeTokens.resolve("default", "mall", "admin").token()).isNull();
    }

    @Test
    void anEmployeeOfASystemWithOnlyALoginApiSignsInWithTheirOwnAccount() {
        passwordProvider("https://mall.example.com/profile", "data.adminUserId", "employee");
        when(http.postBody(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("resultCode", 200, "data", "tok-1"));
        when(http.getJson(eq("https://mall.example.com/profile"), anyMap()))
                .thenReturn(Map.of("data", Map.of("adminUserId", 3, "nickName", "店长")));
        when(userSync.syncKeepingRoles(any())).thenReturn(new SyncedBusinessUser(21L, "default", "local:m", "店长",
                "mall", "3", "店长", List.of()));
        when(userSync.sync(any())).thenReturn(new SyncedBusinessUser(21L, "default", "local:m", "店长",
                "mall", "3", "店长", List.of("employee")));

        BusinessSsoService.PasswordOutcome outcome = service.passwordLogin("mall", "admin", "p\"w", new MockHttpServletRequest());

        assertThat(outcome.error()).isNull();
        assertThat(outcome.location()).startsWith("/my/ai-tools#ticket=");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(http).postBody(eq("https://mall.example.com/login"), eq("application/json"), body.capture(), anyMap());
        assertThat(body.getValue()).startsWith("{\"userName\":\"admin\",\"passwordMd5\":\"").doesNotContain("p\"w");
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(http).getJson(eq("https://mall.example.com/profile"), headers.capture());
        assertThat(headers.getValue()).containsEntry("token", "tok-1");
        ArgumentCaptor<AttestedBusinessUser> granted = ArgumentCaptor.forClass(AttestedBusinessUser.class);
        verify(userSync).sync(granted.capture());
        assertThat(granted.getValue().roles()).containsExactly("employee");
    }

    @Test
    void aWrongPasswordIsRefusedAndRepeatedGuessesAreLockedOut() {
        passwordProvider(null, null, null);
        when(http.postBody(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("resultCode", 500, "message", "登录失败"));

        for (int attempt = 0; attempt < BusinessSsoService.MAX_PASSWORD_FAILURES; attempt++) {
            assertThat(service.passwordLogin("mall", "admin", "guess", new MockHttpServletRequest()).error())
                    .isEqualTo("账号或密码不对");
        }
        BusinessSsoService.PasswordOutcome locked = service.passwordLogin("mall", "admin", "right", new MockHttpServletRequest());

        assertThat(locked.error()).contains("试错次数太多");
        verify(http, org.mockito.Mockito.times(BusinessSsoService.MAX_PASSWORD_FAILURES))
                .postBody(anyString(), anyString(), anyString(), anyMap());
        verify(userSync, never()).syncKeepingRoles(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"X-Forwarded-For", "X-Real-IP"})
    void forwardedHeadersCannotRotateTheAddressUsedToLimitPasswordSpraying(String header) {
        passwordProvider(null, null, null);
        when(http.postBody(anyString(), anyString(), anyString(), anyMap())).thenReturn(Map.of());

        for (int attempt = 0; attempt <= BusinessSsoService.MAX_PASSWORD_FAILURES; attempt++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("192.0.2.10");
            request.addHeader(header, "198.51.100." + attempt
                    + ("X-Forwarded-For".equals(header) ? ", 192.0.2.10" : ""));
            var outcome = service.passwordLogin("mall", "account-" + attempt, "guess", request);
            assertThat(outcome.error()).contains(attempt < BusinessSsoService.MAX_PASSWORD_FAILURES
                    ? "账号或密码不对" : "试错次数太多");
        }

        verify(http, org.mockito.Mockito.times(BusinessSsoService.MAX_PASSWORD_FAILURES))
                .postBody(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void aFullAttemptTrackerRefusesNewKeysBeforeCheckingThePassword() {
        fillPasswordAttemptTracker();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");

        assertThat(service.passwordLogin("mall", "new-account", "guess", request).error())
                .contains("登录的人太多");
        verify(http, org.mockito.Mockito.times(5_000)).postBody(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void aFullAttemptTrackerStillLocksExistingAccountsAndAddresses() {
        fillPasswordAttemptTracker();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.18.0.0");

        for (int attempt = 1; attempt < BusinessSsoService.MAX_PASSWORD_FAILURES; attempt++) {
            assertThat(service.passwordLogin("mall", "account-0", "guess", request).error())
                    .isEqualTo("账号或密码不对");
        }
        assertThat(service.passwordLogin("mall", "account-0", "guess", request).error())
                .contains("试错次数太多");
        verify(http, org.mockito.Mockito.times(5_004)).postBody(anyString(), anyString(), anyString(), anyMap());
    }

    private void fillPasswordAttemptTracker() {
        passwordProvider(null, null, null);
        when(http.postBody(anyString(), anyString(), anyString(), anyMap())).thenReturn(Map.of());
        for (int account = 0; account < 5_000; account++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("198.18." + (account / 256) + "." + (account % 256));
            assertThat(service.passwordLogin("mall", "account-" + account, "guess", request).error())
                    .isEqualTo("账号或密码不对");
        }
    }

    @Test
    void theLoginNameIsTheAccountWhenTheSystemReturnsNoUserInfo() {
        passwordProvider(null, null, null);
        when(http.postBody(anyString(), anyString(), anyString(), anyMap())).thenReturn(Map.of("data", "tok-1"));
        when(userSync.syncKeepingRoles(any())).thenReturn(new SyncedBusinessUser(22L, "default", "local:n", "admin",
                "mall", "admin", "admin", List.of("ops")));

        assertThat(service.passwordLogin("mall", " admin ", "pw", new MockHttpServletRequest()).error()).isNull();

        ArgumentCaptor<AttestedBusinessUser> attested = ArgumentCaptor.forClass(AttestedBusinessUser.class);
        verify(userSync).syncKeepingRoles(attested.capture());
        assertThat(attested.getValue().externalUserId()).isEqualTo("admin");
        verify(userSync, never()).sync(any());
    }

    @Test
    void anAnswerWithoutALoginTokenNeverSignsAnyoneIn() {
        passwordProvider(null, null, null);
        when(http.postBody(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("resultCode", 200, "data", Map.of("hint", "no token here")));

        assertThat(service.passwordLogin("mall", "admin", "anything", new MockHttpServletRequest()).error())
                .isEqualTo("账号或密码不对");
        verify(userSync, never()).syncKeepingRoles(any());
    }

    @Test
    void theLoginTemplateEscapesWhatTheEmployeeTyped() {
        assertThat(BusinessSsoService.render("{\"u\":\"{{username}}\",\"p\":\"{{password}}\"}", "a\"b", "c\\d", false))
                .isEqualTo("{\"u\":\"a\\\"b\",\"p\":\"c\\\\d\"}");
        assertThat(BusinessSsoService.render("u={{username}}&p={{password}}", "a&b", "c=d", true))
                .isEqualTo("u=a%26b&p=c%3Dd");
    }
}
