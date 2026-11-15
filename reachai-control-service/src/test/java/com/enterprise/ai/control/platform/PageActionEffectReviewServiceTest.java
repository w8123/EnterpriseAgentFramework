package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService;
import com.enterprise.ai.control.pageworkbench.application.PageActionEffectReviewStore;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageActionEffectReviewServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PageCatalogApplicationService catalog = mock(PageCatalogApplicationService.class);
    private final PageActionEffectGenerationMapper generations = mock(PageActionEffectGenerationMapper.class);
    private final PageActionEffectReviewMapper reviews = mock(PageActionEffectReviewMapper.class);
    private final PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
    private final PageActionEffectReviewService service = new PageActionEffectReviewService(catalog,
            new PageActionEffectReviewStore(generations, reviews), audit, json);
    private final AtomicLong revision = new AtomicLong(0);
    private final AtomicReference<PageActionEffectReviewEntity> latest = new AtomicReference<>();
    private final PlatformAuthenticatedSession actor = mock(PlatformAuthenticatedSession.class);

    @AfterEach void clearTransaction() { TransactionSynchronizationManager.setActualTransactionActive(false); }

    @Test void trustedDraftContractAcceptsOptionalFieldsAndRejectsRuntimeUnsupportedSchemas() {
        var schema = json.createObjectNode().put("type", "object");
        var fields = schema.putObject("properties");
        fields.putObject("nickName").put("type", "string").put("maxLength", 30);
        fields.putObject("introduceSign").put("type", "string");
        fields.putObject("address").put("type", "string");
        assertDoesNotThrow(() -> PageActionEffectReviewService.validateTrustedDraft("mall", "profile", "fill",
                "填写", "/personal", "build-1", "个人资料", schema));
        for (String route : List.of("/users/:id", "/users/{id}", "/users?q=a", "https://outside", "/users/*",
                "//outside.example/path", "/\\outside.example/path", "/%2foutside", "/user\nname"))
            assertThrows(ResponseStatusException.class, () -> PageActionEffectReviewService.validateTrustedDraft("mall", "profile", "fill",
                    "填写", route, "build-1", "个人资料", schema));
        fields.putObject("postal-code").put("type", "string");
        assertThrows(ResponseStatusException.class, () -> PageActionEffectReviewService.validateTrustedDraft("mall", "profile", "fill",
                "填写", "/personal", "build-1", "个人资料", schema));
        fields.remove("postal-code"); fields.with("address").putArray("enum").add("A");
        assertThrows(ResponseStatusException.class, () -> PageActionEffectReviewService.validateTrustedDraft("mall", "profile", "fill",
                "填写", "/personal", "build-1", "个人资料", schema));
        fields.with("address").remove("enum"); schema.put("additionalProperties", true);
        assertThrows(ResponseStatusException.class, () -> PageActionEffectReviewService.validateTrustedDraft("mall", "profile", "fill",
                "填写", "/personal", "build-1", "个人资料", schema));
    }

    private PageCatalogApplicationService.LockedPageAction fixture() {
        var page = new PageView(1L, 7L, "mall", "profile", null, null, "Profile", null, "/profile", null,
                null, "MANUAL", "ACTIVE", null, null, List.of(), List.of());
        var schema = json.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("nickname").put("type", "string");
        schema.with("properties").putObject("address").put("type", "string");
        var action = new ActionView(2L, 1L, 7L, "mall", "profile", "fill", "Fill", null, "PAGE_ACTION",
                "WRITE", true, null, schema, json.createObjectNode(),
                json.createObjectNode(), List.of(), null, "MANUAL", "ACTIVE", json.createObjectNode(), null);
        var locked = new PageCatalogApplicationService.LockedPageAction(page, action);
        when(catalog.findActiveActionAndPageForUpdate("mall", "profile", "fill")).thenReturn(Optional.of(locked));
        when(generations.lock(2L)).thenAnswer(inv -> revision.get());
        when(generations.invalidateAction(2L)).thenAnswer(inv -> { revision.incrementAndGet(); return 1; });
        when(reviews.insert(any(PageActionEffectReviewEntity.class))).thenAnswer(inv -> {
            PageActionEffectReviewEntity row = inv.getArgument(0); row.setId(revision.get()); latest.set(row); return 1;
        });
        when(reviews.selectOne(any())).thenAnswer(inv -> latest.get());
        when(actor.user()).thenReturn(new PlatformPrincipal(9L, "reviewer", "Reviewer"));
        when(actor.sessionId()).thenReturn("live-session");
        when(actor.hasResourcePermission(PlatformPermissions.WORKFLOW_PUBLISH, "PROJECT", null, "mall")).thenReturn(true);
        when(actor.hasResourcePermission(PlatformPermissions.EMBED_MANAGE, "PROJECT", null, "mall")).thenReturn(true);
        return locked;
    }

    @Test void approvalIsBoundToCurrentGenerationAndRevocationCannotResurrectIt() {
        var locked = fixture();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertTrue(service.resolveApprovedForUpdate("mall", "profile", "fill").isEmpty());
        String hash = PageActionContractFingerprint.of(locked.action(), locked.page(), json);
        var command = new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", hash, "build-1", "个人资料", 0L, true, Map.of("nickname", "昵称", "address", "收货地址"));
        var approval = service.approve(actor, "mall", "profile", "fill", command);
        assertEquals(1, approval.revision());
        assertEquals(approval.approvalId(), service.resolveApprovedForUpdate("mall", "profile", "fill").orElseThrow().approvalId());
        revision.incrementAndGet(); // a committed catalog A -> B -> A change must not restore approval
        assertTrue(service.resolveApprovedForUpdate("mall", "profile", "fill").isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill", command));
        var replacement = service.approve(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", hash, "build-1", "个人资料", 2L, true, Map.of("nickname", "昵称", "address", "收货地址")));
        assertThrows(ResponseStatusException.class, () -> service.revoke(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.RevokeCommand(approval.approvalId(), approval.revision(), approval.catalogHash())));
        service.revoke(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.RevokeCommand(replacement.approvalId(), replacement.revision(), replacement.catalogHash()));
        assertTrue(service.resolveApprovedForUpdate("mall", "profile", "fill").isEmpty());
        verify(audit, times(2)).record(eq(actor), eq("PAGE_ACTION_EFFECT_APPROVED"), anyString(), anyString(), anyMap());
        verify(audit).record(eq(actor), eq("PAGE_ACTION_EFFECT_REVOKED"), anyString(), anyString(), anyMap());
    }

    @Test void rejectsUntrustedActorsStaleHashAndUnacceptedResponsibility() {
        var locked = fixture();
        String hash = PageActionContractFingerprint.of(locked.action(), locked.page(), json);
        var valid = new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", hash, "build-1", "个人资料", 0L, true, Map.of("nickname", "昵称", "address", "收货地址"));
        assertThrows(ResponseStatusException.class, () -> service.approve(null, "mall", "profile", "fill", valid));
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "other", "profile", "fill", valid));
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", "stale", "build-1", "个人资料", 0L, true, Map.of("nickname", "昵称", "address", "收货地址"))));
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", hash, "build-1", "个人资料", 0L, false, Map.of("nickname", "昵称", "address", "收货地址"))));
        when(actor.hasResourcePermission(PlatformPermissions.EMBED_MANAGE, "PROJECT", null, "mall")).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill", valid));
        verify(reviews, never()).insert(any(PageActionEffectReviewEntity.class));
    }

    @Test void rejectsMissingExtraAndAmbiguousFieldLabels() {
        var locked = fixture();
        String hash = PageActionContractFingerprint.of(locked.action(), locked.page(), json);
        for (Map<String, String> labels : List.of(Map.of("nickname", "昵称"),
                Map.of("nickname", "昵称", "address", "昵称"),
                Map.of("nickname", "地址", "address", "收货地址"),
                Map.of("nickname", "昵称", "address", "收货地址", "extra", "其他"))) {
            assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill",
                    new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", hash, "build-1", "个人资料", 0L, true, labels)));
        }
        verify(reviews, never()).insert(any(PageActionEffectReviewEntity.class));
    }

    @Test void rejectsUnsupportedSchemasAndNormalizedAliases() {
        var locked = fixture();
        var schema = (com.fasterxml.jackson.databind.node.ObjectNode) locked.action().inputSchema();
        String originalHash = PageActionContractFingerprint.of(locked.action(), locked.page(), json);
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", originalHash, "build-1", "个人资料", 0L, true,
                        Map.of("nickname", "ＡＢ", "address", "ab"))));
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", originalHash, "build\n1", "个人资料", 0L, true,
                        Map.of("nickname", "昵称", "address", "收货地址"))));
        schema.with("properties").with("nickname").put("type", "array");
        String nestedHash = PageActionContractFingerprint.of(locked.action(), locked.page(), json);
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", nestedHash, "build-1", "个人资料", 0L, true,
                        Map.of("nickname", "昵称", "address", "收货地址"))));
        schema.with("properties").removeAll();
        String emptyHash = PageActionContractFingerprint.of(locked.action(), locked.page(), json);
        assertThrows(ResponseStatusException.class, () -> service.approve(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PAGE_LOCAL_DRAFT", emptyHash, "build-1", "个人资料", 0L, true, Map.of())));
        var persistent = service.approve(actor, "mall", "profile", "fill",
                new PageActionEffectReviewService.ReviewCommand("PERSISTENT", emptyHash, "build-1", "个人资料", 0L, true, Map.of()));
        assertEquals(Map.of(), persistent.fieldLabels());
    }
}
