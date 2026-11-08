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
    private BusinessSsoService service;

    @BeforeEach
    void setUp() {
        service = new BusinessSsoService(store, cipher, http, userSync, portal, consoleLogins, audit);
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
}
